import { appendFileSync, readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const repository = 'DavidPark04/hospital-reservation';
const workflows = ['application-checks.yml', 'repository-checks.yml'];
const shaPattern = /^[0-9a-f]{40}$/;

export function deploymentSha(eventName, event, env) {
  if (env.GITHUB_REPOSITORY !== repository) throw new Error('Unexpected repository');
  let sha;
  if (eventName === 'workflow_run') {
    const run = event.workflow_run;
    if (run?.event !== 'push' || run.head_branch !== 'main'
        || run.head_repository?.full_name !== repository || run.conclusion !== 'success') {
      throw new Error('Only successful checks for a main push may deploy');
    }
    sha = run.head_sha;
  } else if (eventName === 'workflow_dispatch' && env.GITHUB_REF === 'refs/heads/main') {
    sha = env.GITHUB_SHA;
  } else {
    throw new Error('Unexpected deployment event');
  }
  if (!shaPattern.test(sha ?? '')) throw new Error('Invalid deployment SHA');
  return sha;
}

export function latestCheck(runs, sha) {
  return runs.filter(run => run.head_sha === sha && run.head_branch === 'main'
    && run.event === 'push' && run.head_repository?.full_name === repository)
    .sort((a, b) => Date.parse(b.run_started_at || b.created_at) - Date.parse(a.run_started_at || a.created_at)
      || b.id - a.id)[0];
}

export async function hasSuccessfulDeployment(api, sha) {
  const result = await api(`/actions/workflows/vercel-production.yml/runs?branch=main&head_sha=${sha}&per_page=100`);
  for (const run of result.workflow_runs) {
    if (run.head_sha !== sha || run.head_branch !== 'main' || run.conclusion !== 'success'
        || run.head_repository?.full_name !== repository
        || !['workflow_run', 'workflow_dispatch'].includes(run.event)) continue;
    const jobs = await api(`/actions/runs/${run.id}/jobs?per_page=100`);
    if (jobs.jobs.some(job => job.conclusion === 'success'
      && job.steps?.some(step => step.name === 'Deploy checked source' && step.conclusion === 'success'))) return true;
  }
  return false;
}

export async function prepareProduction({ api, sha, synchronize = true, deduplicate = false }) {
  if (!shaPattern.test(sha ?? '')) throw new Error('Invalid deployment SHA');
  const ref = async branch => (await api(`/git/ref/heads/${branch}`)).object.sha;
  if (await ref('main') !== sha) return { ready: false, reason: 'A newer main commit exists' };
  const results = await Promise.all(workflows.map(async workflow => {
    const result = await api(`/actions/workflows/${workflow}/runs?branch=main&event=push&head_sha=${sha}&per_page=100`);
    return { workflow, run: latestCheck(result.workflow_runs, sha) };
  }));
  const failed = results.find(({ run }) => run?.status === 'completed' && run.conclusion !== 'success');
  if (failed) throw new Error(`Latest ${failed.workflow} did not succeed`);
  if (!results.every(({ run }) => run?.status === 'completed' && run.conclusion === 'success')) {
    return { ready: false, reason: 'Waiting for both main checks; their completion triggers another run' };
  }
  if (await ref('main') !== sha) return { ready: false, reason: 'Main changed during checks' };
  const develop = await ref('develop');
  if (develop !== sha) {
    if (!synchronize) throw new Error('Develop no longer matches the checked main commit');
    const comparison = await api(`/compare/${develop}...${sha}`);
    if (comparison.status !== 'ahead' || comparison.merge_base_commit?.sha !== develop) {
      throw new Error('Develop has diverged; automatic synchronization never rewrites history');
    }
    // The second snapshot avoids overwriting work that arrived during the checks.
    if (await ref('main') !== sha) return { ready: false, reason: 'Main changed before synchronization' };
    if (await ref('develop') !== develop) throw new Error('Develop changed before synchronization; retry');
    await api('/git/refs/heads/develop', { method: 'PATCH', body: { sha, force: false } });
  }
  if (await ref('main') !== sha) return { ready: false, reason: 'Main changed before deployment' };
  if (await ref('develop') !== sha) throw new Error('Develop synchronization could not be verified');
  if (deduplicate && await hasSuccessfulDeployment(api, sha)) return { ready: false, reason: 'This exact commit has already been deployed successfully' };
  return { ready: true, sha };
}

async function main() {
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const sha = deploymentSha(process.env.GITHUB_EVENT_NAME, event, process.env);
  const token = process.env.GH_TOKEN;
  if (!token) throw new Error('Missing GitHub workflow token');
  const api = async (path, options = {}) => {
    const response = await fetch(`https://api.github.com/repos/${repository}${path}`, {
      method: options.method || 'GET', redirect: 'error', signal: AbortSignal.timeout(30000),
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json', 'Content-Type': 'application/json', 'X-GitHub-Api-Version': '2022-11-28' },
      body: options.body ? JSON.stringify(options.body) : undefined,
    });
    if (!response.ok) throw new Error(`GitHub ${options.method || 'GET'} ${path.split('?')[0]} returned ${response.status}`);
    return response.json();
  };
  const synchronize = process.argv[2] !== '--check-only';
  const result = await prepareProduction({ api, sha, synchronize, deduplicate: synchronize && process.env.GITHUB_EVENT_NAME === 'workflow_run' });
  if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `ready=${result.ready}\nsha=${sha}\n`);
  const summary = result.ready ? `Verified main and develop at ${sha}.` : `Skipped: ${result.reason}.`;
  console.log(summary);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary + '\n');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
