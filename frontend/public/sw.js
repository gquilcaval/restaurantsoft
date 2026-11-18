// Service worker de RestOS: permite instalar la app y abrirla aunque la red falle por unos segundos.
// Nunca guarda respuestas de la API: pedidos, cobros y caja siempre se consultan en vivo.
const VERSION = 'restos-v1';
const SHELL = ['/', '/index.html', '/manifest.webmanifest', '/icons/icon-192.png', '/icons/icon-512.png'];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(VERSION).then(cache => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', event => {
  event.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(key => key !== VERSION).map(key => caches.delete(key))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws')) return;

  // Páginas: primero la red para recibir siempre la última versión; sin red, la app guardada.
  if (request.mode === 'navigate') {
    event.respondWith(fetch(request)
      .then(response => { const copy = response.clone(); caches.open(VERSION).then(cache => cache.put('/index.html', copy)); return response; })
      .catch(() => caches.match('/index.html')));
    return;
  }

  // Archivos con hash en el nombre: no cambian, así que se sirven desde caché.
  if (/\.(js|css|png|svg|ico|woff2?|webmanifest)$/.test(url.pathname)) {
    event.respondWith(caches.match(request).then(cached => cached || fetch(request).then(response => {
      if (response.ok) { const copy = response.clone(); caches.open(VERSION).then(cache => cache.put(request, copy)); }
      return response;
    })));
  }
});
