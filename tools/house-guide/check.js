// Jarvis house guide: refuse a grab that lost the Huntsville stations, and write status.json (counts only).
const fs = require('fs')
const [file, statusFile] = process.argv.slice(2)
const xml = fs.readFileSync(file, 'utf8')
const counts = {}
for (const m of xml.matchAll(/<programme [^>]*channel="([^"]+)"/g)) counts[m[1]] = (counts[m[1]] || 0) + 1
const must = ['WAFF481.us@HD', 'WHNTTV191.us@HD', 'WAAYTV311.us@HD']
const missing = must.filter(id => !(counts[id] > 0))
if (missing.length) { console.log('missing: ' + missing.join(', ')); process.exit(1) }
fs.writeFileSync(statusFile, JSON.stringify({ grabbed: new Date().toISOString(), programmes: counts }))
