#!/usr/bin/env node
/**
 * PREAMBLE 语义回归（第 92 轮新增）。
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
const SOURCE = path.join(ROOT, 'app/src/main/java/com/adsh/app/core/ptc/QuickJsRuntime.kt');

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
globalThis.__adsh_call__ = (name, raw) => handler(name, raw);
globalThis.__adsh_callAll__ = (payload) =>
  JSON.stringify(JSON.parse(payload).map(([name, raw]) => JSON.parse(handler(name, raw))));

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

  const settled2 = await Promise.allSettled([tools.read({ file_path: 'x' })]);
  check('allSettled rejected branch', [settled2[0].status, settled2[0].reason.name, settled2[0].reason.toolName], [
    'rejected',
    'ToolCallError',
    'read',
  ]);

  const before = calls.length;
  tools.glob({ pattern: '*' }); // 忘了 await：收尾时由 __adsh_flush 补跑
  globalThis.__adsh_flush();
  check('unawaited call still runs (flush)', calls.length, before + 1);

  console.log(failures.length === 0 ? '\nALL OK' : '\n' + failures.length + ' FAILURE(S)');
  process.exit(failures.length === 0 ? 0 : 1);
})();
