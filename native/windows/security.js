"use strict";

const fs = require("node:fs/promises");
const path = require("node:path");

function isTrustedAppUrl(value) {
  try {
    const url = value instanceof URL ? value : new URL(value);
    return url.protocol === "ortogestion:" &&
      url.hostname === "app" &&
      !url.username &&
      !url.password &&
      !url.port;
  } catch {
    return false;
  }
}

async function resolveAppAsset(root, pathname) {
  if (typeof pathname !== "string" || !pathname.startsWith("/") ||
      pathname.includes("\\") || pathname.includes("\0")) {
    throw new Error("Invalid asset path");
  }

  let decoded;
  try {
    decoded = decodeURIComponent(pathname);
  } catch {
    throw new Error("Malformed URL encoding");
  }
  if (decoded.includes("\\") || decoded.includes("\0")) {
    throw new Error("Invalid asset path");
  }

  const normalized = decoded === "/" ? "/index.html" : decoded;
  const realRoot = await fs.realpath(root);
  const candidate = path.resolve(realRoot, "." + normalized);
  const lexicalRelative = path.relative(realRoot, candidate);
  if (lexicalRelative === ".." || lexicalRelative.startsWith(".." + path.sep) ||
      path.isAbsolute(lexicalRelative)) {
    throw new Error("Path traversal blocked");
  }

  const realTarget = await fs.realpath(candidate);
  const relative = path.relative(realRoot, realTarget);
  if (relative === ".." || relative.startsWith(".." + path.sep) ||
      path.isAbsolute(relative)) {
    throw new Error("Resolved path escapes application assets");
  }

  const stat = await fs.stat(realTarget);
  if (!stat.isFile()) throw new Error("Asset is not a regular file");
  return realTarget;
}

module.exports = { isTrustedAppUrl, resolveAppAsset };
