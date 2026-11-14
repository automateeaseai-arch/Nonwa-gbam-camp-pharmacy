// Keeps the app available offline. Shows the saved copy at once and fetches
// the newest version in the background, so updates appear the next time the app is opened.
const CACHE = 'camp-pharmacy-v9';
const SHELL = ['./', 'manifest.webmanifest', 'splash.jpg', 'icon-192.png', 'icon-512.png', 'icon-maskable-512.png'];

self.addEventListener('install', e => e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())));
self.addEventListener('activate', e => e.waitUntil(
  caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())));

const updates = [];
self.addEventListener('fetch', e => {
  const req = e.request, url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== location.origin) return; // database calls go straight to the network
  const key = req.mode === 'navigate' ? './' : req;
  const fresh = caches.open(CACHE).then(c => fetch(req, { cache: 'no-cache' }).then(async r => {
    if (!r.ok) return r;
    const old = await c.match(key, { ignoreSearch: true });
    const tag = x => x.headers.get('etag') || x.headers.get('last-modified');
    const changed = req.mode === 'navigate' && old && tag(old) !== tag(r);
    await c.put(key, r.clone());
    // tell the open app a new version is ready (after a short wait, so the page has finished loading and is listening)
    if (changed) updates.push(new Promise(done => setTimeout(done, 3000)).then(() => self.clients.matchAll()).then(cs => cs.forEach(cl => cl.postMessage('update'))));
    return r;
  }));
  e.waitUntil(fresh.then(() => Promise.all(updates.splice(0))).catch(() => {}));
  e.respondWith(caches.match(key, { ignoreSearch: true }).then(hit => hit || fresh));
});
