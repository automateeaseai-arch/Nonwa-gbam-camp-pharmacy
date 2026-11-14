// Keeps the app available offline. Shows the saved copy at once and fetches
// the newest version in the background, so updates appear the next time the app is opened.
const CACHE = 'camp-pharmacy-v2';
const SHELL = ['./', 'manifest.webmanifest', 'icon.svg', 'icon-192.png', 'icon-512.png'];

self.addEventListener('install', e => e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())));
self.addEventListener('activate', e => e.waitUntil(
  caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())));

self.addEventListener('fetch', e => {
  const req = e.request, url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== location.origin) return; // database calls go straight to the network
  const key = req.mode === 'navigate' ? './' : req;
  const fresh = caches.open(CACHE).then(c => fetch(req, { cache: 'no-cache' }).then(r => { if (r.ok) c.put(key, r.clone()); return r; }));
  e.waitUntil(fresh.catch(() => {}));
  e.respondWith(caches.match(key, { ignoreSearch: true }).then(hit => hit || fresh));
});
