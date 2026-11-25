"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs/promises");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");
const { isTrustedAppUrl, resolveAppAsset } = require("../security");

test("solo acepta el origen local exacto de OrtoGestión", () => {
  assert.equal(isTrustedAppUrl("ortogestion://app/index.html"), true);
  assert.equal(isTrustedAppUrl("ortogestion://app.evil/index.html"), false);
  assert.equal(isTrustedAppUrl("ortogestion://app@evil/index.html"), false);
  assert.equal(isTrustedAppUrl("https://app/index.html"), false);
  assert.equal(isTrustedAppUrl("ortogestion://app:444/index.html"), false);
});

test("sirve index y archivos contenidos dentro de www", async () => {
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), "orto-security-"));
  const root = path.join(temp, "www");
  await fs.mkdir(root);
  await fs.writeFile(path.join(root, "index.html"), "ok");
  await fs.mkdir(path.join(root, "assets"));
  await fs.writeFile(path.join(root, "assets", "app.js"), "ok");
  try {
    assert.equal(await resolveAppAsset(root, "/"), path.join(root, "index.html"));
    assert.equal(await resolveAppAsset(root, "/assets/app.js"), path.join(root, "assets", "app.js"));
  } finally {
    await fs.rm(temp, { recursive: true, force: true });
  }
});

test("bloquea traversal, barras inversas y codificación malformada", async () => {
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), "orto-security-"));
  const root = path.join(temp, "www");
  await fs.mkdir(root);
  await fs.writeFile(path.join(root, "index.html"), "ok");
  const outside = path.join(temp, "secret.txt");
  await fs.writeFile(outside, "secret");
  try {
    for (const value of ["/../secret.txt", "/%2e%2e/secret.txt", "/%2E%2E%2fsecret.txt", "/..%5csecret.txt", "/bad%zz"]) {
      await assert.rejects(resolveAppAsset(root, value));
    }
  } finally {
    await fs.rm(temp, { recursive: true, force: true });
  }
});
