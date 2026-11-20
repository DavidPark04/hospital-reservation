import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { parseEnv } from 'node:util';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const DEFAULT_MONGODB_URI = 'mongodb://127.0.0.1:27118/hospital_reservation?replicaSet=hospital-rs';
export function readLocalConfig(envFile = path.join(root, '.env'), parentEnv = process.env) {
  const fileEnv = fs.existsSync(envFile) ? parseEnv(fs.readFileSync(envFile, 'utf8').replace(/^\uFEFF/, '')) : {};
  return { ...fileEnv, ...parentEnv };
}
export function loadLocalEnv() {
  const env = readLocalConfig();
  const supplied = env.HOSPITAL_API_FILE;
  if (supplied && fs.existsSync(supplied)) {
    const lines = fs.readFileSync(supplied, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/).map(s => s.trim()).filter(Boolean);
    for (let i = 0; i < lines.length - 1; i++) {
      if (lines[i].includes('의료기관별상세정보')) env.HIRA_DETAIL_KEY ||= lines[i + 1];
      else if (lines[i].includes('병원정보서비스')) env.HIRA_HOSPITAL_KEY ||= lines[i + 1];
      else if (/^openai/i.test(lines[i])) env.OPENAI_API_KEY ||= lines[i + 1];
    }
    const providedOpenAiKey = lines.find(line => /^sk-[A-Za-z0-9_-]+$/.test(line));
    if (providedOpenAiKey) env.OPENAI_API_KEY ||= providedOpenAiKey;
  }
  fs.mkdirSync(path.join(root, '.runtime'), { recursive: true });
  const secretFile = path.join(root, '.runtime', 'jwt-secret');
  if (!env.JWT_SECRET) {
    if (!fs.existsSync(secretFile)) fs.writeFileSync(secretFile, crypto.randomBytes(48).toString('base64'), { mode: 0o600 });
    env.JWT_SECRET = fs.readFileSync(secretFile, 'utf8').trim();
  }
  env.OPENAI_MODEL ||= 'gpt-6.2-sol';
  env.MONGODB_URI ||= DEFAULT_MONGODB_URI;
  env.SERVER_PORT ||= '18081';
  env.SERVER_ADDRESS ||= '127.0.0.1';
  env.APP_FRONTEND_URL ||= 'http://127.0.0.1:5174';
  env.APP_BACKEND_URL ||= 'http://127.0.0.1:18081';
  env.SERVER_FORWARD_HEADERS_STRATEGY ||= 'none';
  env.DEMO_ENABLED ||= 'true';
  return env;
}
