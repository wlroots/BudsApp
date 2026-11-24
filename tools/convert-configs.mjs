#!/usr/bin/env node
// Converts the per-model device configs from BudsLink (GPL-3.0) into
// app/src/main/assets/models.json so the Android app uses the same capability
// tables instead of a hand-transcribed copy.
//
// Usage: node tools/convert-configs.mjs [path-to-BudsLink-deviceConfigs]

import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';

const defaultSource = path.resolve(
    process.env.HOME ?? '',
    'Projects/BudsReference/BudsLink/src/lib/devices/nothingBuds/deviceConfigs',
);
const sourceDir = path.resolve(process.argv[2] ?? defaultSource);
const outFile = path.resolve('app/src/main/assets/models.json');
const tmpDir = path.resolve('.tmp-configs');

if (!fs.existsSync(sourceDir)) {
    console.error(`Config directory not found: ${sourceDir}`);
    process.exit(1);
}

fs.rmSync(tmpDir, {recursive: true, force: true});
fs.mkdirSync(tmpDir, {recursive: true});
fs.mkdirSync(path.dirname(outFile), {recursive: true});
fs.writeFileSync(path.join(tmpDir, 'package.json'), '{"type":"module"}\n');

const files = fs.readdirSync(sourceDir).filter(f => f.endsWith('.js')).sort();
const models = [];

for (const file of files) {
    const target = path.join(tmpDir, file.replace(/\.js$/, '.mjs'));
    fs.copyFileSync(path.join(sourceDir, file), target);

    const module = await import(pathToFileURL(target).href);
    const config = module.default;
    if (!config || typeof config !== 'object')
        throw new Error(`${file}: no default config object`);

    const pattern = config.pattern instanceof RegExp
        ? {source: config.pattern.source, flags: config.pattern.flags}
        : null;

    // JSON.stringify drops undefined values; RegExp is replaced above.
    models.push({...config, pattern});
}

models.sort((a, b) => a.name.localeCompare(b.name));

fs.writeFileSync(outFile, JSON.stringify({
    source: 'BudsLink deviceConfigs (GPL-3.0), converted for BudsApp',
    version: 1,
    models,
}, null, 2) + '\n');

fs.rmSync(tmpDir, {recursive: true, force: true});
console.log(`Wrote ${models.length} models to ${path.relative(process.cwd(), outFile)}`);
for (const model of models)
    console.log(`  ${model.modelId}  ${model.name}`);
