// node kitchen/mod/test/live-card-buttons.cjs
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = readFileSync(path.join(__dirname, '../../retwitch-patches/resources/retwitch-rn.js'), 'utf8');

for (let mask = 0; mask < 8; mask++) {
    const config = {hideLiveMute: !!(mask & 1), hideLiveMore: !!(mask & 2), hideLiveFollow: !!(mask & 4)};
    const context = {__retwitchRn: config};
    vm.runInNewContext(source, context);
    context.__d = (factory, exports) => factory(null, null, null, null, {exports});
    function load(exports) { context.__d(() => {}, exports); }
    load({memo() {}, useState() {}, createElement: (type, props) => type(props)});
    const original = props => props;
    const icons = {IconButton: original}, follow = {FeedFollowButton: original};
    const barrel = {get IconButton() { return icons.IconButton; }};
    load(barrel);
    load(icons);
    load(follow);
    function check(component, testID, hidden) {
        const props = {testID, onPress() {}, ref: {}};
        assert.equal(component(props), hidden ? null : props);
    }
    check(barrel.IconButton, 'feed-mute-channel', config.hideLiveMute);
    check(barrel.IconButton, 'feed-more-channel', config.hideLiveMore);
    check(follow.FeedFollowButton, 'feed-follow-channel', config.hideLiveFollow);
    for (const id of ['feed-clip-mute-x', 'feed-clip-more-x', 'feed-ad-mute', 'player-controls-mute', undefined]) {
        check(barrel.IconButton, id, false);
    }
    check(follow.FeedFollowButton, 'feed-clip-follow-slug', false);
}
console.log('Live card button combinations OK');
