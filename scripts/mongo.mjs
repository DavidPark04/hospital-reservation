import { MongoMemoryReplSet } from 'mongodb-memory-server-core';
import fs from 'node:fs';
import path from 'node:path';
import { root } from './local-env.mjs';

const databasePath = path.join(root, '.runtime', 'mongodb-data');
fs.mkdirSync(databasePath, {recursive:true});
// This launches a real mongod with WiredTiger files, NOT an in-memory mock database.
const replica = await MongoMemoryReplSet.create({
  binary: { version: '8.0.16', downloadDir: path.join(root, '.runtime', 'mongodb-bin') },
  instanceOpts: [{port:27118, dbPath:databasePath}],
  replSet: { name:'hospital-rs', count:1, ip:'127.0.0.1', storageEngine:'wiredTiger' }
});
console.log('Local MongoDB replica set ready on 127.0.0.1:27118 (persistent WiredTiger storage).');
fs.writeFileSync(path.join(root, '.runtime', 'mongo-ready.json'), JSON.stringify({port:27118,pid:process.pid,readyAt:new Date().toISOString()}));
async function shutdown() { await replica.stop({doCleanup:false,force:false}); process.exit(0); }
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
process.on('message', message => { if(message?.type==='shutdown') shutdown(); });
setInterval(() => {}, 60_000);
