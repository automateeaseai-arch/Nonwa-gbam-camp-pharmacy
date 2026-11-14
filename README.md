# Camp Pharmacy Records

Offline-first medication record for the pharmacy at Nonwa Gbam NYSC orientation camp.
Replaces the paper ledger: staff type a corps member's state code and see the drugs,
what is due this session, and how many doses have been taken.

## Current state
`index.html` is the whole app (HTML + CSS + JS in one file, no build step).

- Patients are keyed by state code with punctuation stripped (`RV/26C/4110` -> `RV26C4110`).
  At registration `RV/` and the second `/` are fixed; staff type the batch (`26C`, remembered per phone) and the number (padded to 4 digits).
- Journey: register patient -> prescription (date, diagnosis) -> drugs -> give doses.
- A drug has a dose, sessions (Morning / Afternoon / Night), days, first-dose date and session.
  Total doses = days x sessions. Every dose is a fixed slot (date + session).
- A slot that passes without being given is "missed"; the schedule moves on regardless.
  A missed slot can be marked given afterwards.
- Sessions by clock: Morning < 12:00, Afternoon 12:00-17:00, Night after 17:00.
- A drug can be stopped or edited; a later visit gets a new prescription; old ones stay in history.
- Home screen lists who is expected in each session today.

## Data model
Each patient is one record: `{id, code, name, sex, platoon, phone, allergies, updatedAt, deleted, entries[]}`.
`entries` holds three item types, each with `id`, `updatedAt`, optional `deleted` (tombstone):
- `rx`   `{t:'rx', date, diagnosis, by}`
- `drug` `{t:'drug', rxId, drug, dose, times[], days, startDate, startSession, stopped, stopDate, stopSession}`
- `dose` `{t:'dose', drugId, date, s, by, at}` with id `drugId|date|session`, so two devices giving the same dose merge into one.

## Files
- `index.html` the whole app (no build step).
- `sw.js`, `manifest.webmanifest`, `icon*` make it an installable app that opens offline. `splash.jpg` is the loading page.
- `supabase/schema.sql` database tables, access rules and the merge function. Run once in the Supabase SQL Editor.

## Storage and sync
- Local: IndexedDB (falls back to localStorage, then memory with a warning banner). The phone is the working copy.
- Cloud: Supabase (Postgres), called with plain `fetch` (no library), so nothing extra must load offline.
  - Pull: `GET /rest/v1/patients?server_ts=gt.<last sync - 2 min>`.
  - Push: `rpc/sync_push` merges on the server under a row lock: patient fields newest `updatedAt` wins,
    entries merged per item by `updatedAt`. Two phones never overwrite each other's doses.
- Phones that ran the old Google Sheet version upload all their records on first start.
  Backups (JSON) from the old version can be loaded in Settings.

## Access
- Staff sign in with email and password (Supabase Auth). Internet is needed for the first sign-in only.
- Only emails in the `staff` table can read or write, even if sign-ups are left open.
- Only admins (`staff.is_admin`) can delete a patient or a prescription. The buttons are hidden for other staff,
  and `sync_push` undoes such a delete if one arrives anyway. Any staff member can stop or delete a single drug.
- Signing out removes records from that phone (blocked while uploads are pending).

## Admin: adding a staff member
1. Supabase -> Authentication -> Users -> Add user -> Create new user (email + password, tick "Auto confirm").
2. SQL Editor: `insert into staff (email, name) values ('them@example.com', 'Their Name');`
3. Authentication -> Sign In / Providers: turn off "Allow new users to sign up".

## Reports
Home -> Reports (or Settings -> Reports). Pick a start and end date; everything is counted by prescription date.
Shows statistics (patients, prescriptions, doses given/missed, diagnoses, drugs, sex, platoon, per day) and the full record.
"Save as PDF / Print" uses the browser's print screen, so it works offline and needs no library.

## End of camp
Settings -> End of camp -> Delete all records (admins only). A backup must be downloaded first and the word DELETE typed.
`wipe_all` deletes every row in `patients` and changes `camp.epoch`. Each phone compares that value on its next sync
and clears its own copy; `sync_push` refuses uploads that carry an old value, so old records cannot come back.

## Keeping the database awake
Supabase pauses free projects after about a week without activity. `.github/workflows/keepalive.yml`
reads the `ping` view twice a week. If it fails, GitHub emails the repository owner.
If the project does get paused: Supabase dashboard -> Restore project. Phones keep working offline meanwhile.

## Testing
Tested in headless Chrome with two simulated phones against the schema in a local Postgres 16
(wrong password, non-staff account, offline edits on both phones, offline reload, merge, sign out).

## Next steps
- Host on GitHub Pages so phones can install it.
