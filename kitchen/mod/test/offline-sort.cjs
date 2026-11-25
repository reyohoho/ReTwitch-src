// node kitchen/mod/test/offline-sort.cjs
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = readFileSync(path.join(__dirname, '../../retwitch-patches/resources/retwitch-rn.js'), 'utf8');
const make = (type, props, ...kids) => ({type, props: {...props, ...(kids.length ? {children: kids.length === 1 ? kids[0] : kids} : {})}});
const clone = (e, props, ...kids) => make(e.type, {...e.props, ...props}, ...kids);
const url = 'http://localhost/test', config = {mode: 0, url, title: 'Sort', stock: 'Stock', frequency: 'Most watched', alpha: 'A to Z'};
let serverMode = 0, ranks = {}, calls = 0, state = [], index, effects = [], textBase = '#efeff1';
function IconFilter() {}
const listeners = new Map();
const events = {addEventListener: listen, addListener: listen};
function listen(name, callback) { listeners.set(name, callback); return {remove() { listeners.delete(name); }}; }
function patch(enabled, original) {
    const ctx = {__retwitchRn: enabled ? {offlineSort: config} : {}, fetch: async (endpoint, options) => {
        calls++;
        if (options.method === 'POST') serverMode = Number(new URL(endpoint).searchParams.get('mode'));
        return {ok: true, json: async () => ({mode: serverMode, ranks: {...ranks}})};
    }};
    vm.runInNewContext(source, ctx);
    ctx.__d = (fn, exports) => fn(null, null, null, null, {exports});
    function load(exports) { ctx.__d(() => {}, exports); return exports; }
    load({memo: c => c, createElement: make, cloneElement: clone,
        useState(initial) {const k = index++; if (!(k in state)) state[k] = initial; return [state[k], v => {state[k] = v;}];},
        useRef(initial) {const k = index++; return state[k] ||= {current: initial};},
        useMemo(fn) {return fn();},
        useEffect(fn, deps) {const k = index++, old = state[k]; if (!old || deps.some((v, i) => v !== old[i])) {state[k] = deps; effects.push(fn);}},
    });
    load({AppRegistry: {}, Pressable: 'Pressable', StyleSheet: {flatten: s => s}, View: 'View', Text: 'Text', AppState: events, DeviceEventEmitter: events});
    load({IconFilter});
    load({IconButton: 'Button', DropDownMenu: 'Menu', useTheme: () => ({colors: {textBase}})});
    return load({FollowingOfflineChannels: original}).FollowingOfflineChannels;
}
const visit = () => {};
function row(login, name, i) {return make('Row', {edge: {node: {login, displayName: name}}, newVideoCount: 5 - i, onOpenProfile: visit, itemPosition: i, gatesAppLoad: i === 0});}
let rows = [row('b', 'Bee', 0), row('a', 'Alpha', 1), row('c', 'Sea', 2)], empty = false;
let shelfMargin = -8;
function original() {
    if (empty) return null;
    return make('Fragment', {}, null, make('Section', {testID: 'following-offline', style: {marginHorizontal: shelfMargin}},
        make('Title', {}), make('List', {onLayout: visit, children: rows})), null);
}
const wrapped = patch(true, original);
assert.equal(patch(false, original), original);
empty = true;
assert.equal(wrapped({enabled: true}), null);
assert.equal(calls, 0);
empty = false;
function render(enabled = true) {
    index = 0;
    const shelf = wrapped({enabled}).props.children[1];
    const section = shelf.type(shelf.props);
    for (const fn of effects.splice(0)) fn();
    const [header, list] = section.props.children;
    const [button, menu] = header.props.children[0].props.children[1].props.children;
    return {rows: list.props.children, list, button, menu, header, section};
}
const settle = () => new Promise(r => setImmediate(r));
const logins = out => Array.from(out.rows, r => r.props.edge.node.login).join(',');
(async () => {
    let out = render(); await settle(); out = render(); assert.equal(logins(out), 'b,a,c');
    for (shelfMargin of [-8, -12, 0]) {
        out = render();
        assert.ok(out.header.props.style.marginHorizontal + shelfMargin === 0);
        assert.equal(out.section.props.style.marginHorizontal, shelfMargin);
    }
    for (textBase of ['#efeff1', '#0e0e10']) {
        const icon = out.button.props.icon;
        assert.equal(typeof icon, 'object', 'Twitch IconButton renders its icon prop as a child');
        const filter = icon.type(icon.props);
        assert.equal(filter.type, IconFilter);
        assert.equal(filter.props.size, 24);
        assert.equal(filter.props.fillColor, textBase);
    }
    out.button.props.onPress(); out = render(); assert.equal(out.menu.props.show, true);
    out.menu.props.items[2].onPress(); await settle(); out = render();
    assert.equal(logins(out), 'a,b,c');
    assert.equal(out.rows[0].props.itemPosition, 0); assert.ok(out.rows[0].props.gatesAppLoad);
    assert.equal(out.rows[0].props.newVideoCount, 4); assert.equal(out.rows[0].props.onOpenProfile, visit);
    assert.equal(out.list.props.onLayout, visit);
    ranks = {c: 100, a: 20}; out.menu.props.items[1].onPress(); await settle(); out = render(); assert.equal(logins(out), 'c,a,b');
    ranks = {b: 400}; listeners.get('TwitchRNFeedVisibility:state')({visible: true}); await settle(); out = render(); assert.equal(logins(out), 'b,a,c');
    rows = [...rows, row('d', 'Aardvark', 3)]; out = render();
    out.menu.props.items[2].onPress(); await settle(); out = render(); assert.equal(logins(out), 'd,a,b,c');
    out.menu.props.items[0].onPress(); await settle(); out = render(); assert.equal(logins(out), 'b,a,c,d');
    console.log('Offline shelf: stock, duration, A–Z, pagination, returning, hidden state and row actions OK');
})().catch(e => {console.error(e); process.exitCode = 1;});
