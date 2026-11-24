// node kitchen/mod/test/following-header.cjs
// FlashList keeps a mounted header and unchanged cells when Following receives new live edges.
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = readFileSync(path.join(__dirname, '../../retwitch-patches/resources/retwitch-rn.js'), 'utf8');
const context = {__retwitchRn: {favorites: [{id: 'one'}]}};
vm.runInNewContext(source, context);
context.__d = (factory, exports) => factory(null, null, null, null, {exports});
function load(exports) { context.__d(() => {}, exports); return exports; }

let rendering;
const layout = [];
load({
    memo: fn => fn, useState: value => [value, () => {}], useEffect() {},
    useMemo: fn => fn(), useLayoutEffect: fn => layout.push(fn),
    Fragment: 'Fragment', createElement: (type, props, ...children) => ({type, children}),
    useSyncExternalStore(subscribe, read) {
        const component = rendering;
        if (!component.unsubscribe) {
            component.snapshot = read();
            component.unsubscribe = subscribe(() => {
                if (component.snapshot !== read()) {
                    component.snapshot = read();
                    component.render();
                }
            });
        }
        return component.snapshot;
    },
});
load({AppRegistry: {}, Pressable: {}, View: 'View'});

let data;
const {useFollowingTabData} = load({useFollowingTabData: () => data});
const originalHeader = function LiveHeader() {};
const {FollowingLiveHeader} = load({FollowingLiveHeader: originalHeader});
const {TrackedFollowingChannelRow} = load({TrackedFollowingChannelRow: function Row() {}});
function edge(id) { return {node: {stream: {broadcaster: {id}}}}; }
function update(ids) {
    data = {liveEdges: ids.map(edge)};
    const sorted = useFollowingTabData().liveEdges.map(e => e.node.stream.broadcaster.id);
    layout.splice(0).forEach(fn => fn());
    return sorted.join(',');
}
function mount(draw) {
    const component = {render() {
        rendering = component;
        component.tree = draw();
        rendering = undefined;
    }};
    component.render();
    return component;
}
function headerCount(tree) {
    if (!tree) return 0;
    return Number(tree.type === originalHeader) + tree.children.reduce((n, child) => n + headerCount(child), 0);
}

assert.equal(update(['two']), 'two');
const header = mount(() => FollowingLiveHeader({}));
assert.equal(headerCount(header.tree), 1);

// The old header stays mounted. A new live favorite puts Twitch's "Live" header below its row.
assert.equal(update(['two', 'one']), 'one,two');
const favoriteRow = mount(() => TrackedFollowingChannelRow({cellKey: 'live-row-one', stream: edge('one').node.stream}));
assert.equal(headerCount(header.tree), 0);
assert.equal(headerCount(favoriteRow.tree), 1);

// Last ordinary live channel ends. Its removal must update the already-mounted favorite row.
assert.equal(update(['one']), 'one');
assert.equal(headerCount(favoriteRow.tree), 0);

// Favorite goes offline. The original header goes back above ordinary live channels.
assert.equal(update(['two']), 'two');
assert.equal(headerCount(header.tree), 1);
assert.equal(headerCount(favoriteRow.tree), 0);
favoriteRow.unsubscribe();
header.unsubscribe();
console.log('Following header transitions OK');
