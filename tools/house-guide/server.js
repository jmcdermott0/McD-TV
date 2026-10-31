// Jarvis house guide: serves the grabbed guide to the TVs on the home network (port 8787). Only these three
// files, read-only. Run by launchd (com.mcd.jarvisguide.serve).
const http = require('http')
const fs = require('fs')
const path = require('path')
const WWW = path.join(process.env.HOME, 'Library/Application Support/JarvisGuide/www')
const FILES = { '/huntsville.xml.gz': 'application/gzip', '/huntsville.xml': 'application/xml', '/status.json': 'application/json' }
http.createServer((req, res) => {
  const type = FILES[req.url.split('?')[0]]
  if (!type || (req.method !== 'GET' && req.method !== 'HEAD')) { res.writeHead(404); return res.end() }
  const f = path.join(WWW, req.url.split('?')[0])
  fs.stat(f, (err, st) => {
    if (err) { res.writeHead(503); return res.end() }
    res.writeHead(200, { 'Content-Type': type, 'Content-Length': st.size, 'Cache-Control': 'max-age=600', 'Last-Modified': st.mtime.toUTCString() })
    if (req.method === 'HEAD') return res.end()
    fs.createReadStream(f).pipe(res)
  })
}).listen(8787, '0.0.0.0')
