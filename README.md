# Camp Pharmacy Records

Offline-first medication record for the pharmacy at Nonwa Gbam NYSC orientation camp.
Replaces the paper ledger: staff type a corps member's state code and see the drugs,
what is due this session, and how many doses have been taken.

## Current state
`index.html` is the whole app (HTML + CSS + JS in one file, no build step).

- Patients are keyed by state code with punctuation stripped (`RV/26C/4110` -> `RV26C4110`).
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

## Storage and sync
- Local: IndexedDB (falls back to localStorage, then memory with a warning banner).
- Cloud: optional Google Apps Script web app writing to a Google Sheet (script is embedded in Settings).
  Sync is pull -> merge -> push; patient fields are last-write-wins, entries merge per item by `updatedAt`.
- Tested in headless Chrome with two simulated devices. The Apps Script side has only been run against a mock.

## Next steps (requested by the owner)
1. Put the project on GitHub.
2. Replace the Google Sheet with a real database (e.g. Supabase/Postgres or Firebase), keeping offline-first sync.
3. Host it as an installable PWA (service worker) so phones can open it offline; a local file is unreliable on Android.
4. Add staff sign-in, since these are patient records.
