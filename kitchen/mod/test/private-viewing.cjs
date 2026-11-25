// node kitchen/mod/test/private-viewing.cjs
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../retwitch-patches/resources/retwitch-rn.js'), 'utf8');

for (const enabled of [false, true]) {
    const endpoint = 'http://127.0.0.1:9/process-secret/clip.mp4';
    const context = {__retwitchRn: {privateClipEndpoint: endpoint, privateViewEvents: enabled ? ['video-play', 'minute-watched'] : null}};
    vm.runInNewContext(source, context);
    context.__d = (factory, exports) => factory(null, null, null, null, {exports});
    const pending = [];
    const module = {
        createSpadeReporter() {
            return {track(name) { pending.push(name); }, flush() { return pending.length; }};
        },
        signClipRenditionUrl(rendition, token) { return rendition.sourceURL + '?sig=' + token.signature; },
        resolveLiveUrl(channel, options) { return options; },
        resolveLiveUrlSync(channel, options) { return options.preFetchedToken || null; },
    };
    const barrel = {};
    for (const key of Object.keys(module)) Object.defineProperty(barrel, key, {get: () => module[key]});
    context.__d(() => {}, barrel);
    context.__d(() => {}, module);

    const reporter = barrel.createSpadeReporter();
    for (const name of ['video-play', 'follow', 'minute-watched']) reporter.track(name);
    assert.deepEqual(pending, enabled ? ['follow'] : ['video-play', 'follow', 'minute-watched']);
    assert.equal(reporter.flush(), pending.length);

    const options = {preFetchedToken: {}, getAuthToken() {}, cacheBuster: 7};
    const request = barrel.resolveLiveUrl('channel', options);
    assert.equal(request.getAuthToken, enabled ? undefined : options.getAuthToken);
    assert.equal(barrel.resolveLiveUrlSync('channel', options), enabled ? null : options.preFetchedToken);
    assert.equal(request.cacheBuster, 7);
    assert.ok(options.preFetchedToken && options.getAuthToken);

    const rendition = {sourceURL: 'https://clips-media-assets.twitch.tv/folder/720p.mp4?tracking_id=account'};
    const clipToken = {value: JSON.stringify({clip_slug: 'SomeClip-ABC', user_id: 'private-id'}), signature: 'private-signature'};
    const signed = barrel.signClipRenditionUrl(rendition, clipToken);
    if (enabled) {
        const local = new URL(signed);
        assert.equal(local.origin + local.pathname, endpoint);
        assert.deepEqual(Array.from(local.searchParams.entries()), [['slug', 'SomeClip-ABC'], ['asset', '/folder/720p.mp4']]);
        assert.equal(barrel.signClipRenditionUrl(rendition, null), endpoint);
        assert.equal(barrel.signClipRenditionUrl(rendition, {value: '{}'}), endpoint);
        assert.equal(barrel.signClipRenditionUrl({}, clipToken), undefined);
    } else {
        assert.ok(signed.includes(clipToken.signature));
    }
}
console.log('Private viewing RN checks passed');
