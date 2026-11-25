// node kitchen/mod/test/wallpaper-theme.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../retwitch-patches/resources/retwitch-rn.js'), 'utf8');

function loadThemes(wallpaper) {
    const context = {__retwitchRn: {wallpaper}};
    vm.runInNewContext(source, context);
    context.__d = (factory, exports) => factory(null, null, null, null, {exports});
    const stock = () => ({colors: {backgroundBase: '#18181b', textBase: '#efeff1', textLive: '#eb0400'}});
    const themes = {darkTheme: stock(), lightTheme: stock(), overlayTheme: stock(), commonColors: {}};
    context.__d(() => {}, themes);
    return themes;
}
const palette = {neutral1_900: '#211a21', neutral1_50: '#f2eef2'};
const patched = loadThemes(palette);
assert.equal(patched.darkTheme.colors.backgroundBase, palette.neutral1_900);
assert.equal(patched.lightTheme.colors.backgroundBase, palette.neutral1_50);
assert.equal(patched.darkTheme.colors.textBase, palette.neutral1_50);
assert.equal(patched.lightTheme.colors.textBase, palette.neutral1_900);
assert.equal(patched.darkTheme.colors.textLive, '#eb0400');
assert.equal(patched.overlayTheme.colors.backgroundBase, '#18181b');
assert.equal(loadThemes(null).darkTheme.colors.backgroundBase, '#18181b');
console.log('Wallpaper colours: both themes, stock overlay, status colours and opt-out OK');
