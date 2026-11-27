// 只复制离线回放实际读取的输入；不复制整个世界，也不改动安装目录。
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const [source, destination] = process.argv.slice(2);
if (!source || !destination) throw Error('用法：node freeze-pack-replay.cjs <实例目录> <新的快照目录>');
const root = fs.realpathSync(source);
const name = path.basename(root);
const pack = path.join('versions', name);
const dest = path.resolve(destination);
if (dest === root || dest.startsWith(root + path.sep)) throw Error('请将快照放在实例目录以外');
const files = [];
const add = (relative, prefix = pack, base = root) => {
    const file = path.join(base, relative);
    if (fs.existsSync(file) && fs.statSync(file).isFile()) files.push([file, path.join(prefix, relative)]);
};
add(name + '.jar');
add(name + '.json');
const profile = path.join(root, name + '.json');
if (fs.existsSync(profile)) {
    const game = JSON.parse(fs.readFileSync(profile, 'utf8')).arguments?.game ?? [];
    const arg = key => game.includes(key) ? game[game.indexOf(key) + 1] : '';
    const mc = arg('--fml.mcVersion'), forge = arg('--fml.forgeVersion');
    if ([mc, forge].every(v => /^\d+(?:\.\d+)+$/.test(v))) {
        const version = mc + '-' + forge;
        add(`libraries/net/minecraftforge/forge/${version}/forge-${version}-universal.jar`,
            '', path.dirname(path.dirname(root)));
    }
}
for (const jar of fs.readdirSync(path.join(root, 'mods')).filter(s => s.endsWith('.jar'))) add(path.join('mods', jar));
for (const script of fs.readdirSync(path.join(root, 'kubejs/server_scripts'), {recursive: true}).filter(s => s.endsWith('.js')))
    add(path.join('kubejs/server_scripts', script));
for (const file of ['config/enigmaticlegacy-common.omniconf', 'config/enigmaticdelicacy-common.omniconf', 'scripts/wujin.zs'])
    add(file);
function findDisks(dir, depth) {
    for (const entry of fs.readdirSync(path.join(root, dir), {withFileTypes: true})) {
        const relative = path.join(dir, entry.name);
        if (entry.isDirectory() && depth < 3) findDisks(relative, depth + 1);
        if (entry.isFile() && entry.name === 'refinedstorage_disks.dat') {
            add(relative);
            add(path.join(path.dirname(dir), 'serverconfig/irons_spellbooks-server.toml'));
        }
    }
}
findDisks('saves', 1);

fs.mkdirSync(path.dirname(dest), {recursive: true});
fs.mkdirSync(dest); // 快照不可原位覆盖。
for (const directory of ['mods', 'saves', 'kubejs/server_scripts'])
    fs.mkdirSync(path.join(dest, pack, directory), {recursive: true});
const hashes = {};
for (const [src, relative] of files.sort((a, b) => a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0)) {
    const out = path.join(dest, relative);
    fs.mkdirSync(path.dirname(out), {recursive: true});
    fs.copyFileSync(src, out, fs.constants.COPYFILE_EXCL);
    hashes[relative.split(path.sep).join('/')] = crypto.createHash('sha256').update(fs.readFileSync(out)).digest('hex');
}
const manifest = JSON.stringify(hashes, null, 2) + '\n';
fs.writeFileSync(path.join(dest, 'manifest.json'), manifest);
console.log(`snapshot files=${files.length} sha256=${crypto.createHash('sha256').update(manifest).digest('hex')} pack=${path.join(dest, pack)}`);
