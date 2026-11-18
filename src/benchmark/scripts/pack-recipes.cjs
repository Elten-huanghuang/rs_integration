const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// 只用于本机信任的脚本。模拟配方事件，不模拟 Java 对象和世界状态。
const scripts = path.join(process.argv[2], 'kubejs', 'server_scripts');
const recipes = [];
const removals = [];
const failures = [];

const prelude = `
const captured = [], removed = [];
const ignored = new Proxy(() => ignored, {get: () => ignored});
function add(recipe) {
    const entry = {recipe};
    captured.push(entry);
    const chain = new Proxy({
        id: value => { entry.id = String(value); return chain; },
    }, {get: (target, key) => target[key] ?? (() => {
        entry.unsupported = String(key);
        return chain;
    })});
    return chain;
}
const event = new Proxy({
    custom: add,
    shaped: (result, pattern, key) => add({type: 'minecraft:crafting_shaped', result, pattern, key}),
    shapeless: (result, ingredients) => add({type: 'minecraft:crafting_shapeless', result, ingredients}),
    remove: rule => removed.push(rule),
}, {get: (target, key) => target[key] ?? ignored});
const Item = {of: (item, amount = 1, nbt) => {
    const stack = {item, count: typeof amount === 'number' ? amount : 1};
    if (typeof amount === 'string' || nbt != null) stack.nbt = nbt ?? amount;
    Object.defineProperties(stack, {
        strongNBT: {value: () => stack},
        weakNBT: {value: () => { stack.unsupported = 'weakNBT'; return stack; }},
        withNBT: {value: value => { stack.nbt = value; return stack; }},
        enchant: {value: () => { stack.unsupported = 'enchant'; return stack; }},
    });
    return stack;
}};
const Ingredient = {of: value => value};
const ServerEvents = new Proxy({recipes: callback => callback(event)}, {get: (target, key) => target[key] ?? ignored});
const ItemEvents = ignored, BlockEvents = ignored, EntityEvents = ignored, PlayerEvents = ignored;
const console = {log() {}, warn() {}, error() {}};
`;

for (const relative of fs.readdirSync(scripts, {recursive: true}).filter(name => name.endsWith('.js')).sort()) {
    const code = fs.readFileSync(path.join(scripts, relative), 'utf8');
    if (!code.includes('ServerEvents.recipes')) continue;
    const context = vm.createContext({}, {codeGeneration: {strings: false, wasm: false}});
    vm.runInContext(prelude, context);
    try {
        vm.runInContext(code, context, {filename: relative, timeout: 300});
    } catch (error) {
        failures.push({file: relative, message: error.message});
    }
    const data = JSON.parse(vm.runInContext('JSON.stringify({captured, removed})', context, {timeout: 300}));
    for (const row of data.captured) {
        if (row.recipe) recipes.push({...row, source: relative});
    }
    for (const row of data.removed) {
        if (row && typeof row === 'object' && !Array.isArray(row)) removals.push(row);
    }
}
process.stdout.write(JSON.stringify({recipes, removals, failures}));
