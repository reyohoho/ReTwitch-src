// node kitchen/mod/test/clip-card-buttons.cjs
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = readFileSync(path.join(__dirname, '../../retwitch-patches/resources/retwitch-rn.js'), 'utf8');
const actions = ['avatar', 'reactions', 'comments', 'share', 'mute', 'aspect', 'more', 'follow'];
const slug = 'name-with-hyphens';
const id = action => 'feed-clip-' + action + '-' + slug;
const element = (type, props, ...children) => ({type, props: children.length ? {...props, children: children[0]} : props});
const isElement = x => !!x && !!x.type;
function walk(node, visit) {
    if (Array.isArray(node)) node.forEach(child => walk(child, visit));
    else if (isElement(node)) {
        if (typeof node.type === 'function') walk(node.type(node.props), visit);
        else { visit(node); walk(node.props.children, visit); }
    }
}

for (let mask = 0; mask < 256; mask++) {
    const hidden = Object.fromEntries(actions.filter((_, i) => mask & (1 << i)).map(a => ['hide_clip_' + a, true]));
    const context = {__retwitchRn: mask ? {hiddenClipButtons: hidden} : {}};
    vm.runInNewContext(source, context);
    context.__d = (factory, exports) => factory(null, null, null, null, {exports});
    const load = exports => { context.__d(() => {}, exports); return exports; };
    let scope;
    const memo = type => ({type});
    load({memo, useState() {}, isValidElement: isElement, createElement: element,
        cloneElement: (el, props) => ({...el, props: {...el.props, ...props}}),
        useMemo: fn => fn(), useEffect: fn => fn(), useContext: c => c.value,
        createContext: value => (scope = {value, Provider: 'HomeClipsProvider'})});
    load({AppRegistry: {}, Pressable: {}, View: 'View'});
    const stockPage = props => props.children;
    const stockCard = memo(props => props.tree);
    const page = load({ClipsFeedPage: stockPage}).ClipsFeedPage;
    const card = load({FeedClipItem: stockCard}).FeedClipItem;
    if (!mask) {
        assert.equal(page, stockPage);
        assert.equal(card, stockCard);
        continue;
    }
    const provider = page({});
    assert.equal(provider.type, scope.Provider);
    assert.equal(provider.props.value, true);
    assert.equal(card.type({}).type, stockCard); // Identical clip card on another screen stays stock.
    scope.value = true;
    for (const live of [false, true]) {
        let avatarReady = 0, followHidden = 0;
        const button = (a, props = {}) => element('Button', {testID: id(a), ...props});
        const avatar = element('Avatar', {onAvatarLoad: () => avatarReady++, ...(live
            ? {testID: id('live'), viewersTestID: id('viewers')} : {press: {testID: id('avatar-press')}})});
        const follow = button('follow', {onVisibilityChange: visible => { assert.equal(visible, false); followHidden++; }});
        const wrapped = a => element('View', {block: a, children: button(a)});
        const right = element('View', {children: [avatar, wrapped('reactions'), wrapped('comments'),
            ...['share', 'mute', 'aspect', 'more'].map(a => button(a))]});
        const title = button('title');
        const overlay = element('View', {children: element('ThemeRegion', {theme: 'overlay',
            children: [element('View', {children: [title, follow]}), right]})});
        const player = button('surface'), sheet = button('comments-sheet'), progress = button('scrubber');
        const tree = element('View', {children: [player, overlay, progress, sheet]});
        const props = {node: {content: {slug}}, tree};
        const filtered = card.type(props).type.type(props);
        const seen = new Set(), blocks = new Set();
        walk(filtered, node => { seen.add(node.props.testID || node.props.press?.testID); blocks.add(node.props.block); });
        for (const [i, a] of actions.entries()) {
            const visible = !(mask & (1 << i));
            assert.equal(seen.has(id(a === 'avatar' ? live ? 'live' : 'avatar-press' : a)), visible, a);
            if (a === 'comments' || a === 'reactions') assert.equal(blocks.has(a), visible);
        }
        assert.equal(avatarReady, Number(!!hidden.hide_clip_avatar));
        assert.equal(followHidden, Number(!!hidden.hide_clip_follow));
        assert(seen.has(title.props.testID));
        assert.equal(filtered.props.children[0], player);
        assert.equal(filtered.props.children[2], progress);
        assert.equal(filtered.props.children[3], sheet);
    }
}
console.log('Home Clips: all 256 control combinations, both avatar states and screen scope OK');
