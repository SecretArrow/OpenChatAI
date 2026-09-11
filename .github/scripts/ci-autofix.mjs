#!/usr/bin/env node
/**
 * CI auto-fix via LLM (OpenAI-compatible API).
 *
 * Input : .ci/failed-logs-trim.txt (log kegagalan build)
 * Output: menimpa/membuat file sesuai patch dari model, atau no-op bila model
 *         tidak yakin. Tidak pernah menyentuh .github/ atau workflow.
 *
 * Env: AI_API_KEY (wajib), AI_BASE_URL (default https://api.openai.com/v1),
 *      AI_MODEL (default gpt-4o-mini)
 */
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { execSync } from "node:child_process";
import path from "node:path";

const API_KEY = process.env.AI_API_KEY;
if (!API_KEY) {
  console.log("AI_API_KEY tidak di-set — lewati auto-fix AI.");
  process.exit(0);
}
const BASE_URL = (process.env.AI_BASE_URL || "https://api.openai.com/v1").replace(/\/$/, "");
const MODEL = process.env.AI_MODEL || "gpt-4o-mini";

const logPath = ".ci/failed-logs-trim.txt";
if (!existsSync(logPath)) {
  console.log("Log kegagalan tidak ditemukan — lewati.");
  process.exit(0);
}
const failedLogs = readFileSync(logPath, "utf8");

// Daftar file sumber yang relevan ( hindari file biner / gradle wrapper )
let fileList = [];
try {
  fileList = execSync("git ls-files", { encoding: "utf8" })
    .split("\n")
    .filter((f) =>
      /\.(kt|kts|xml|properties|pro|md)$/.test(f) &&
      !f.startsWith(".ci/") &&
      !f.includes("gradle-wrapper")
    );
} catch (_) {}

// Kirim maksimal 60 file kecil sebagai konteks (header + bagian awal file)
const CONTEXT_BUDGET = 120_000;
let contextBudget = CONTEXT_BUDGET;
const fileSnippets = [];
for (const f of fileList) {
  if (contextBudget <= 0) break;
  try {
    const stat = execSync(`wc -c "${f}"`, { encoding: "utf8" });
    const size = parseInt(stat.trim().split(/\s+/)[0], 10);
    if (size > 60_000) continue;
    let content = readFileSync(f, "utf8");
    if (content.length > 8_000) content = content.slice(0, 8_000) + "\n// …(truncated)";
    const chunk = `### FILE: ${f}\n${content}\n`;
    if (chunk.length > contextBudget) break;
    contextBudget -= chunk.length;
    fileSnippets.push(chunk);
  } catch (_) {}
}

const system = [
  "You are an expert Android build fixer.",
  "You receive failing Gradle/Kotlin CI logs and the current source files.",
  "Return STRICT JSON only (no markdown fences):",
  '{"summary":"short cause analysis","fixes":[{"path":"relative/path.kt","content":"FULL new file content"}]}',
  "Rules:",
  "- Only fix compile errors and Gradle config problems.",
  "- Each fix must contain the COMPLETE new file content.",
  "- Never modify .github/, workflows, or gradle wrapper files.",
  "- If you are not confident, return an empty fixes array.",
].join("\n");

const user = [
  "FAILED CI LOGS (tail):",
  "```",
  failedLogs,
  "```",
  "CURRENT SOURCE FILES:",
  ...fileSnippets,
].join("\n");

async function callAI() {
  const res = await fetch(`${BASE_URL}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${API_KEY}`,
    },
    body: JSON.stringify({
      model: MODEL,
      temperature: 0,
      messages: [
        { role: "system", content: system },
        { role: "user", content: user },
      ],
    }),
  });
  if (!res.ok) {
    throw new Error(`AI API error ${res.status}: ${await res.text()}`);
  }
  const data = await res.json();
  return data.choices?.[0]?.message?.content || "";
}

function extractJson(text) {
  const cleaned = text.replace(/```json|```/g, "").trim();
  const start = cleaned.indexOf("{");
  const end = cleaned.lastIndexOf("}");
  if (start < 0 || end <= start) return null;
  try {
    return JSON.parse(cleaned.slice(start, end + 1));
  } catch (_) {
    return null;
  }
}

try {
  const raw = await callAI();
  const parsed = extractJson(raw);
  if (!parsed || !Array.isArray(parsed.fixes)) {
    console.log("Model tidak menghasilkan JSON yang valid — tidak ada patch diterapkan.");
    process.exit(0);
  }
  let applied = 0;
  for (const fix of parsed.fixes) {
    const p = String(fix.path || "");
    const content = String(fix.content ?? "");
    if (!p || !content || p.includes("..") || p.startsWith("/") || p.startsWith(".github/")) continue;
    if (!fileList.includes(p) && !/\.(kt|kts|xml|properties|pro)$/.test(p)) continue;
    mkdirSync(path.dirname(p), { recursive: true });
    writeFileSync(p, content, "utf8");
    applied++;
    console.log(`patched: ${p}`);
  }
  console.log(`SUMMARY: ${parsed.summary || "(no summary)"}`);
  console.log(`Applied ${applied} file fix(es).`);
} catch (err) {
  console.error(`Auto-fix gagal (dibiarkan lewat): ${err.message}`);
}
process.exit(0);
