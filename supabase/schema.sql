-- Camp Pharmacy Records: database setup for Supabase.
-- Run once: Supabase dashboard -> SQL Editor -> New query -> paste all of this -> Run.
-- Safe to run again (it only creates what is missing and replaces the functions).

-- 1. Staff list. Only emails listed here can read or write records,
--    even if someone else manages to create an account.
create table if not exists public.staff (
  email    text primary key,
  name     text not null default '',
  is_admin boolean not null default false
);
alter table public.staff enable row level security;
drop policy if exists "staff read own row" on public.staff;
create policy "staff read own row" on public.staff
  for select to authenticated using (lower(email) = lower(auth.jwt() ->> 'email'));

create or replace function public.is_staff() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.staff where lower(email) = lower(auth.jwt() ->> 'email'));
$$;

-- 2. Patients. One row per patient; the whole record (details, prescriptions,
--    drugs, doses) is in `data`, the same shape the app stores on the phone.
create table if not exists public.patients (
  id         text primary key,                 -- state code without punctuation, e.g. RV26C4110
  data       jsonb not null,
  server_ts  bigint not null default 0,        -- ms since 1970, set on every write; used for "what changed since"
  updated_by text
);
create index if not exists patients_server_ts on public.patients (server_ts);
alter table public.patients enable row level security;
drop policy if exists "staff read patients" on public.patients;
create policy "staff read patients" on public.patients
  for select to authenticated using (public.is_staff());
-- No insert/update/delete policies: all writes go through sync_push below.

-- 3. Upload changes. Merges each record with what is already stored, so two
--    phones editing the same patient never wipe out each other's entries:
--    patient details: newest updatedAt wins; entries (rx/drug/dose): merged one by one, newest updatedAt wins.
--    Only admins may delete a patient or a prescription: such a change from anyone else is undone here.
create or replace function public.sync_push(changes jsonb) returns bigint
language plpgsql security definer set search_path = public as $$
declare
  c jsonb; old jsonb; merged jsonb; ents jsonb; pid text; adm boolean;
  ts bigint := (extract(epoch from clock_timestamp()) * 1000)::bigint;
begin
  select is_admin into adm from staff where lower(email) = lower(auth.jwt() ->> 'email');
  if adm is null then raise exception 'This account is not on the staff list'; end if;
  for c in select * from jsonb_array_elements(coalesce(changes, '[]'::jsonb)) loop
    pid := c ->> 'id';
    if coalesce(pid, '') = '' then continue; end if;
    insert into patients (id, data) values (pid, '{}'::jsonb) on conflict (id) do nothing;
    select data into old from patients where id = pid for update;
    if not adm then
      if coalesce((c ->> 'deleted')::boolean, false) and old ? 'id' and not coalesce((old ->> 'deleted')::boolean, false) then
        c := (old - 'entries') || jsonb_build_object('entries', coalesce(c -> 'entries', '[]'::jsonb),
          'updatedAt', greatest(ts, coalesce((c ->> 'updatedAt')::numeric, 0) + 1));
      end if;
      select coalesce(jsonb_agg(case
          when ne ->> 't' = 'rx' and coalesce((ne ->> 'deleted')::boolean, false) and oe is not null and not coalesce((oe ->> 'deleted')::boolean, false)
          then oe || jsonb_build_object('updatedAt', greatest(ts, coalesce((ne ->> 'updatedAt')::numeric, 0) + 1))
          else ne end), '[]'::jsonb) into ents
        from jsonb_array_elements(coalesce(c -> 'entries', '[]'::jsonb)) as n(ne)
        left join jsonb_array_elements(coalesce(old -> 'entries', '[]'::jsonb)) as o(oe) on oe ->> 'id' = ne ->> 'id';
      c := jsonb_set(c, '{entries}', ents);
    end if;
    merged := case when coalesce((c ->> 'updatedAt')::numeric, 0) >= coalesce((old ->> 'updatedAt')::numeric, 0) then c else old end;
    select coalesce(jsonb_agg(z.e order by z.e ->> 'id'), '[]'::jsonb) into ents from (
      select distinct on (u.e ->> 'id') u.e from (
        select e, 0 as src from jsonb_array_elements(coalesce(old -> 'entries', '[]'::jsonb)) e
        union all
        select e, 1 from jsonb_array_elements(coalesce(c -> 'entries', '[]'::jsonb)) e
      ) u
      order by u.e ->> 'id', coalesce((u.e ->> 'updatedAt')::numeric, 0) desc, u.src desc
    ) z;
    merged := jsonb_set(merged - 'entries', '{entries}', ents);
    update patients set data = merged, server_ts = ts, updated_by = auth.jwt() ->> 'email' where id = pid;
  end loop;
  return ts;
end $$;

-- Signed-in users may read (the policies above still limit this to staff). No write grants: writes go through sync_push.
grant select on public.staff, public.patients to authenticated;

revoke all on function public.sync_push(jsonb) from public, anon;
grant execute on function public.sync_push(jsonb) to authenticated;
revoke all on function public.is_staff() from public, anon;
grant execute on function public.is_staff() to authenticated;

-- 4. Keep-awake check. Holds no data; .github/workflows/keepalive.yml reads it twice a week
--    so the free project is not paused for inactivity.
create or replace view public.ping as select 1 as ok;
grant select on public.ping to anon, authenticated;

-- 5. Add your staff (edit and run; one line per person):
-- insert into public.staff (email, name, is_admin) values
--   ('you@example.com', 'Pharm. Your Name', true),
--   ('colleague@example.com', 'Colleague Name', false)
-- on conflict (email) do update set name = excluded.name, is_admin = excluded.is_admin;
