import { readFile, access } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';
const web = resolve(process.argv[2]);
assert.ok(!(await readFile(resolve(web, 'src/pages/order/index.vue'), 'utf8')).includes('url(/static/'), 'dynamic dish art must use the application prefix');
const require = createRequire(resolve(web, 'package.json'));
const ts = require('typescript');
let source = await readFile(resolve(web, 'src/api/http.ts'), 'utf8');
source = source.replace('import.meta.env.VITE_API_BASE_URL', "'/chuxiaoban'");
const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText;
const storage = new Map();
let initCount = 0, protectedCount = 0;
globalThis.uni = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key),
  request: options => setTimeout(() => {
    if (options.url.endsWith('/sync/guest/init')) {
      initCount++;
      options.success({ statusCode: 200, data: { code: 'OK', data: { access_token: 'valid-fixture-token', refresh_token: 'fixture-refresh' } } });
    } else {
      protectedCount++;
      const status = options.header.Authorization === 'Bearer valid-fixture-token' ? 200 : 401;
      options.success({ statusCode: status, data: { code: status === 200 ? 'OK' : 'UNAUTHORIZED', data: [] } });
    }
  }, 5)
};
const api = await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'));
await Promise.all([api.request({url:'/api/v1/sync/guest/init',method:'POST'}),api.request({url:'/api/v1/household/dishes'}),api.request({url:'/api/v1/knowledge/recent'})]);
assert.equal(initCount, 1, 'initialization must be shared');
assert.equal(protectedCount, 2);
storage.set('cxb_access_token', 'expired-fixture-token');
await api.request({url:'/api/v1/household/dishes'});
assert.equal(initCount, 2, 'expired token must refresh once');
assert.equal(protectedCount, 4, 'protected request retried once');
for (const name of ['icons/home-active.svg','icons/camera.svg','icons/knowledge.svg','icons/profile.svg','images/dishes-pixel.png']) {
  await access(resolve(web, 'dist/build/h5/static', name));
}
console.log('PASS: concurrent first visit, expired session recovery, and required original static assets');
