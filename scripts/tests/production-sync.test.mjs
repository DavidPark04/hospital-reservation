import test from 'node:test';
import assert from 'node:assert/strict';
import { deploymentSha, latestCheck, prepareProduction, hasSuccessfulDeployment } from '../prepare-production.mjs';

const sha = 'a'.repeat(40), previous = 'b'.repeat(40), newer = 'c'.repeat(40);
const repository = 'DavidPark04/hospital-reservation';
const good = { id: 1, head_sha: sha, head_branch: 'main', event: 'push', head_repository: { full_name: repository }, status: 'completed', conclusion: 'success', run_started_at: '2026-11-21T00:00:00Z' };
function fixture(options = {}) {
  let main = options.main ?? sha, develop = options.develop ?? previous, patches = [];
  let checkReads = 0, refReads = 0;
  const api = async (path, request) => {
    if (path === '/git/ref/heads/main') {
      refReads++;
      if (options.changeMainAt === refReads) main = newer;
      return { object: { sha: main } };
    }
    if (path === '/git/ref/heads/develop') return { object: { sha: develop } };
    if (path.startsWith('/actions/workflows/')) {
      checkReads++;
      return { workflow_runs: options.checks ? options.checks(path, checkReads) : [good] };
    }
    if (path.startsWith('/compare/')) return { status: options.diverged ? 'diverged' : 'ahead', merge_base_commit: { sha: options.diverged ? newer : previous } };
    if (path === '/git/refs/heads/develop') {
      assert.deepEqual(request, { method: 'PATCH', body: { sha, force: false } });
      patches.push(request);
      if (options.denied) throw new Error('GitHub returned 403');
      develop = request.body.sha;
      return { object: { sha: develop } };
    }
    throw new Error(`Unexpected API path: ${path}`);
  };
  return { api, patches, getDevelop: () => develop };
}

test('rejects fork, PR, develop, unsuccessful and malformed events before API use', () => {
  const env = { GITHUB_REPOSITORY: repository };
  assert.equal(deploymentSha('workflow_run', { workflow_run: good }, env), sha);
  for (const override of [{ event: 'pull_request' }, { head_branch: 'develop' }, { conclusion: 'failure' }, { head_repository: { full_name: 'other/repo' } }, { head_sha: '../main' }]) {
    assert.throws(() => deploymentSha('workflow_run', { workflow_run: { ...good, ...override } }, env));
  }
  assert.throws(() => deploymentSha('workflow_dispatch', {}, { ...env, GITHUB_REF: 'refs/heads/develop', GITHUB_SHA: sha }));
  assert.equal(deploymentSha('workflow_dispatch', {}, { ...env, GITHUB_REF: 'refs/heads/main', GITHUB_SHA: sha }), sha);
});

test('an older success cannot hide the latest failure or a rerun', () => {
  const failed = { ...good, id: 0, conclusion: 'failure', run_started_at: '2026-11-21T01:00:00Z' };
  assert.equal(latestCheck([good, failed, { ...good, id: 3, event: 'pull_request' }], sha), failed);
});

test('successful exact main checks fast-forward only develop, retaining the same SHA', async () => {
  const f = fixture();
  assert.deepEqual(await prepareProduction({ api: f.api, sha }), { ready: true, sha });
  assert.equal(f.patches.length, 1);
  assert.equal(f.getDevelop(), sha);
});

test('idempotent synchronization permits retrying deployment after a previous failure', async () => {
  const f = fixture({ develop: sha });
  assert.equal((await prepareProduction({ api: f.api, sha })).ready, true);
  assert.equal(f.patches.length, 0);
});

test('failed main checks cannot move develop', async () => {
  for (const runs of [[{ ...good, conclusion: 'failure' }], [{ ...good, conclusion: 'cancelled' }]]) {
    const f = fixture({ checks: () => runs });
    await assert.rejects(prepareProduction({ api: f.api, sha }));
    assert.equal(f.patches.length, 0);
  }
});

test('missing, pending and wrong-SHA checks defer to their own completion event', async () => {
  for (const runs of [[], [{ ...good, status: 'in_progress', conclusion: null }], [{ ...good, head_sha: previous }]]) {
    const f = fixture({ checks: path => path.includes('repository-checks') ? runs : [good] });
    assert.equal((await prepareProduction({ api: f.api, sha })).ready, false);
    assert.equal(f.patches.length, 0);
  }
});

test('stale and superseded main commits are skipped without a branch write', async () => {
  for (const options of [{ main: newer }, { changeMainAt: 2 }, { changeMainAt: 3 }]) {
    const f = fixture(options);
    assert.equal((await prepareProduction({ api: f.api, sha })).ready, false);
    assert.equal(f.patches.length, 0);
  }
});

test('diverged develop and denied writes fail instead of forcing history', async () => {
  for (const options of [{ diverged: true }, { denied: true }]) {
    const f = fixture(options);
    await assert.rejects(prepareProduction({ api: f.api, sha }));
    assert.equal(f.getDevelop(), previous);
  }
});

test('pre-deployment recheck cannot repair unexpected develop changes', async () => {
  const f = fixture();
  await assert.rejects(prepareProduction({ api: f.api, sha, synchronize: false }));
  assert.equal(f.patches.length, 0);
});

test('a skipped deployment run never prevents a later successful deployment', async () => {
  for (const conclusion of ['skipped', 'failure', 'success']) {
    const api = async path => path.includes('/jobs?')
      ? { jobs: [{ conclusion: 'success', steps: [{ name: 'Deploy checked source', conclusion }] }] }
      : { workflow_runs: [{ ...good, event: 'workflow_run' }] };
    assert.equal(await hasSuccessfulDeployment(api, sha), conclusion === 'success');
  }
});

test('automatic duplicate events skip a successful deploy; manual retry remains available', async () => {
  const f = fixture({ develop: sha });
  const api = async (path, request) => path.includes('/vercel-production.yml/')
    ? { workflow_runs: [{ ...good, event: 'workflow_run' }] }
    : path.includes('/jobs?') ? { jobs: [{ conclusion: 'success', steps: [{ name: 'Deploy checked source', conclusion: 'success' }] }] }
      : f.api(path, request);
  assert.equal((await prepareProduction({ api, sha, deduplicate: true })).ready, false);
  assert.equal((await prepareProduction({ api, sha, deduplicate: false })).ready, true);
});
