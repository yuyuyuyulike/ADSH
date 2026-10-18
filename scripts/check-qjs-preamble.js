#!/usr/bin/env node
/**
 * PREAMBLE 语义回归。
 *
 * 为什么要有它：`QuickJsRuntime` 的 PREAMBLE 是一段**只在真机上跑**的 JS，Gradle 的 JVM 单测
 * 碰不到它 —— 于是 `Promise.all([tools.x()])` 坏了很久没人发现（`__adsh_runAll` 把已经解析好的
 * 对象又交给 `__adsh_parse`，二次 JSON.parse 把对象转成 "[object Object]" 直接抛
 * `SyntaxError: unexpected token: 'object'`；模型按提示词写的并发代码必然失败）。
 *
 * 这里用 Node 把同一段 PREAMBLE 拉出来、用桩替掉宿主函数，跑一遍关键语义。
 * Node 与 QuickJS 在这些原生语义（thenable、Promise.all/allSettled、JSON.parse）上一致。
 *
 * 用法：node scripts/check-qjs-preamble.js   （退出码非 0 = 有回归）
 * 脚本自己从 Kotlin 源码里抽 PREAMBLE —— 改提示词/运行时不用同步改这里。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SOURCE = path.join(
  ROOT,
  'platform/termux/src/main/java/com/adsh/app/platform/ptc/QuickJsRuntime.kt',
);

function extractPreamble() {
  const src = fs.readFileSync(SOURCE, 'utf8');
  const start = src.indexOf('"""', src.indexOf('private val PREAMBLE'));
  if (start < 0) throw new Error('PREAMBLE not found in ' + SOURCE);
  const end = src.indexOf('"""', start + 3);
  if (end < 0) throw new Error('PREAMBLE is not terminated');
  return src.slice(start + 3, end).replace(/\$\{'\$'\}/g, '$');
}

const calls = [];
let handler = (name, raw) => {
  calls.push([name, JSON.parse(raw)]);
  if (name === 'read') return JSON.stringify({ path: 'x', lines: [{ number: 1, text: 'hi' }], totalLines: 1 });
  return JSON.stringify({ root: '.', paths: ['a', 'b'] });
};

globalThis.__adsh_names = ['bash', 'glob', 'read', 'web_search'];
/* 宿主的两个入口（旧的同步协议 __adsh_call__ / __adsh_callAll__ 已删除，只留异步调用）：
   tools.x() -> __adsh_invoke -> __adsh_post__(id, name, raw)，宿主做活后由泵循环调
   __adsh_settleAll__([[id, wire]]) 兑现。这里桩成立刻做活并兑现，语义等价。 */
const posts = [];
globalThis.__adsh_post__ = (id, name, raw) => {
  posts.push([id, name, raw]);
  let wire;
  try {
    wire = handler(name, raw);
  } catch (e) {
    wire = JSON.stringify({
      __error: true,
      message: String((e && e.message) || e),
      toolName: name,
      retryable: false,
      code: 'EXEC',
    });
  }
  globalThis.__adsh_settleAll__([[id, wire]]);
};

eval(extractPreamble());

const failures = [];
function check(label, got, want) {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  console.log((ok ? 'OK   ' : 'FAIL ') + label + '  -> ' + JSON.stringify(got));
  if (!ok) failures.push(label + ': want ' + JSON.stringify(want));
}

(async () => {
  const two = await Promise.all([tools.glob({ pattern: '*' }), tools.read({ file_path: 'x' })]);
  check('Promise.all(two lazy calls)', two.map((v) => (v.paths ? 'glob' : 'read')), ['glob', 'read']);

  const one = await Promise.all([tools.glob({ pattern: '*' })]);
  check('Promise.all(one lazy call)', [Array.isArray(one), one.length], [true, 1]);

  const mixed = await Promise.all([tools.glob({ pattern: '*' }), 7]);
  check('Promise.all(lazy + plain value)', [mixed[1], Array.isArray(mixed[0].paths)], [7, true]);

  const chained = await tools.glob({ pattern: '*' })
    .then((v) => 'ok:' + v.paths.length)
    .catch((e) => 'err:' + e.message);
  check('then().catch() chaining', chained, 'ok:2');

  /* 直接挂 .catch / .finally（实测报告第 9 点）：dsh 的绑定是 async 函数（真 Promise），
     所以那边这两个方法一直都在；ADSH 的惰性 handle 以前只有 then，模型直接 .catch 会拿到
     "not a function"。这里钉住补上之后的行为。 */
  const directCatch = await tools.glob({ pattern: '*' }).catch(() => 'never');
  check('direct .catch() passes a success through', Array.isArray(directCatch.paths), true);

  let finRuns = 0;
  const finValue = await tools.glob({ pattern: '*' }).finally(() => { finRuns++; });
  check('.finally() runs once and passes the value through', [finRuns, finValue.paths.length], [1, 2]);

  const settled = await Promise.allSettled([tools.glob({ pattern: '*' }), tools.read({ file_path: 'x' })]);
  check('allSettled statuses', settled.map((s) => s.status), ['fulfilled', 'fulfilled']);

  // 失败通道：单调用可 catch；批量调用 reject 成 ToolCallError；allSettled 走 rejected 分支
  handler = (name, raw) => {
    calls.push([name, JSON.parse(raw)]);
    return name === 'read'
      ? JSON.stringify({ __error: true, message: 'nope', toolName: 'read', retryable: true, code: 'EACCES' })
      : JSON.stringify({ ok: 1 });
  };

  let rejected = null;
  try {
    await Promise.all([tools.read({ file_path: 'x' })]);
  } catch (e) {
    rejected = e;
  }
  check('Promise.all rejects with ToolCallError', rejected && [rejected.name, rejected.toolName, rejected.code, rejected.retryable], [
    'ToolCallError',
    'read',
    'EACCES',
    true,
  ]);

  const caught = await tools.read({ file_path: 'x' })
    .then(() => 'ok')
    .catch((e) => 'caught:' + e.toolName + ':' + e.message);
  check('single-call rejection is catchable', caught, 'caught:read:nope');

  const directCaught = await tools.read({ file_path: 'x' }).catch((e) => 'direct:' + e.toolName + ':' + e.message);
  check('direct .catch() catches a ToolCallError', directCaught, 'direct:read:nope');

  let finRuns2 = 0;
  const finRejected = await tools.read({ file_path: 'x' }).finally(() => { finRuns2++; }).catch(() => 'rejected');
  check('.finally() on rejection runs and the rejection continues', [finRuns2, finRejected], [1, 'rejected']);

  const callsBefore = calls.length;
  await tools.glob({ pattern: '*' }).catch(() => null);
  check('direct .catch() still dispatches the tool exactly once', calls.length - callsBefore, 1);

  const settled2 = await Promise.allSettled([tools.read({ file_path: 'x' })]);
  check('allSettled rejected branch', [settled2[0].status, settled2[0].reason.name, settled2[0].reason.toolName], [
    'rejected',
    'ToolCallError',
    'read',
  ]);

  const postsBefore = posts.length;
  await tools.glob({ pattern: '*' });
  check('每次调用都立刻发出调用帧（__adsh_post__）', posts.length - postsBefore, 1);

  const beforeCalls = calls.length;
  const beforePosts = posts.length;
  tools.glob({ pattern: '*' }); // 忘了 await：调用帧在 __adsh_invoke 里同步发出，不靠收尾补跑
  check('unawaited call still dispatches', [calls.length - beforeCalls, posts.length - beforePosts], [1, 1]);

  /* ---------------------------------------------------------------- 参数 lossless JSON
     dsh 的 ptc.ts jsonNormalizeArgs + util/values walkJsonValue：参数只允许无损 JSON。
     两种文案：没给参数对象 → no-arguments 原文；其余非法值 → "tool arguments must be lossless JSON: <原因>"。
     校验发生在发帧之前，所以每次拒绝都必须**没有**调用帧。 */
  const NO_ARGS = 'tool arguments must be lossless JSON (call the tool with an arguments object, e.g. `{}`)';
  const BEFORE_SEND = 'tool arguments must be lossless JSON: ';
  /** 无参调用的哨兵（undefined 会被 wrapper 当「没给参数对象」） */
  const NO_ARGUMENT = Symbol('no-argument');

  async function rejects(label, args, wantMessage) {
    const before = posts.length;
    let err = null;
    try {
      if (args === NO_ARGUMENT) await tools.glob();
      else await tools.glob(args);
    } catch (e) {
      err = e;
    }
    const pass = err instanceof ToolCallError
      && err.name === 'ToolCallError'
      && err.toolName === 'glob'
      && err.message === wantMessage
      && posts.length === before;
    console.log((pass ? 'OK   ' : 'FAIL ') + label + '  -> ' + (err ? JSON.stringify(err.message) : 'resolved') +
      ', frames=' + (posts.length - before));
    if (!pass) {
      failures.push(label + ': want ToolCallError(' + JSON.stringify(wantMessage) + '), dispatched=' +
        (posts.length - before) + ', got=' + (err ? err.name + '/' + err.message : 'resolved'));
    }
  }

  const sparse = [1];
  sparse[2] = 3;
  const cyclic = { pattern: '*' };
  cyclic.self = cyclic;
  class NotJson {}
  const hidden = { pattern: '*' };
  Object.defineProperty(hidden, 'hidden', { value: 1, enumerable: false });

  await rejects('no arguments rejects with the dsh no-arguments message', NO_ARGUMENT, NO_ARGS);
  await rejects('a root function is not JSON', () => 1, NO_ARGS);
  await rejects('a function property is not JSON', { pattern: '*', f: () => 1 }, NO_ARGS);
  await rejects('an undefined property is not JSON', { pattern: '*', a: undefined }, NO_ARGS);
  await rejects('a symbol value is not JSON', { pattern: '*', s: Symbol('x') }, NO_ARGS);
  await rejects('NaN is not JSON', NaN, NO_ARGS);
  await rejects('Infinity is not JSON', Infinity, NO_ARGS);
  await rejects('-0 is not JSON', -0, NO_ARGS);
  await rejects('a class instance is not JSON', new NotJson(), NO_ARGS);
  await rejects('a Date is not JSON', new Date(0), NO_ARGS);
  await rejects('a cyclic object is not JSON', cyclic, NO_ARGS);
  await rejects('a sparse array is not JSON', { pattern: '*', a: sparse }, NO_ARGS);
  await rejects('a non-enumerable property is not JSON', hidden, NO_ARGS);
  await rejects(
    'a throwing getter reports the walk failure',
    { get pattern() { throw new Error('boom'); } },
    BEFORE_SEND + 'boom',
  );

  const lossless = { pattern: '*', count: 0, nested: { list: [1, null, 'x'] } };
  const beforeAllowed = posts.length;
  await tools.glob(lossless);
  check(
    'a lossless object dispatches the exact JSON it was given',
    [posts.length - beforeAllowed, posts[posts.length - 1][2]],
    [1, JSON.stringify(lossless)],
  );

  const beforeNull = posts.length;
  await tools.glob(null);
  check('a null root is lossless JSON and dispatches', [posts.length - beforeNull, posts[posts.length - 1][2]], [1, 'null']);

  const concurrent = await Promise.all([tools.glob({ pattern: '*' }), tools.glob({ pattern: '**/*.kt' })]);
  check('Promise.all still passes lossless arguments through', concurrent.map((v) => v.ok), [1, 1]);

  console.log(failures.length === 0 ? '\nALL OK' : '\n' + failures.length + ' FAILURE(S)');
  // 用 exitCode 而不是 process.exit：管道 / 重定向下 stdout 是异步写，立刻 exit 会把输出截掉。
  process.exitCode = failures.length === 0 ? 0 : 1;
})();
