const { test } = require('node:test');
const assert = require('node:assert/strict');
const { webcrypto } = require('node:crypto');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = readFileSync(path.resolve(__dirname, '../../main/resources/maestro-web.js'), 'utf8');
const selector = '.action';

class Element {
    constructor(tag, id = '', children = []) {
        this.localName = tag;
        this.tagName = tag.toUpperCase();
        this.id = id;
        this.attributes = {};
        this.children = children;
    }
    getAttribute(name) { return name === 'id' ? this.id : null; }
    getBoundingClientRect() { return { x: 10, y: 20, width: 40, height: 40 }; }
    contains(other) { return this === other || this.children.some(child => child.contains(other)); }
}

function page() {
    const label = new Element('span');
    const button = new Element('button', '', [label]);
    const sibling = new Element('button');
    const overlay = new Element('div', 'cover');
    const body = new Element('body', '', [sibling, button, overlay]);
    const hits = { receiver: label, points: [], matches: [sibling, button] };
    const document = {
        body,
        readyState: 'complete',
        querySelectorAll(css) { return css === selector ? hits.matches : []; },
        elementFromPoint(x, y) { hits.points.push([x, y]); return hits.receiver; },
    };
    const window = { innerWidth: 200, innerHeight: 100 };
    function inject(arkWeb = true) {
        vm.runInNewContext(script, { window, document, Node: Element, Element, crypto: webcrypto });
        Object.assign(window.maestro, { viewportX: 30, viewportY: 400, viewportWidth: 600, viewportHeight: 200 });
        window.maestro.arkWebTapIdentityEnabled = arkWeb;
        return window.maestro;
    }
    return { window, inject, hits, button, sibling, overlay };
}

test('only ArkWeb opts into stable DOM identity across queries and reinjection', () => {
    const { inject } = page();
    const first = inject(false).queryCss(selector);
    assert.equal(first[1].attributes['arkweb-node-id'], undefined);
    const web = inject();
    const id = web.queryCss(selector)[1].attributes['arkweb-node-id'];
    assert.ok(id);
    assert.equal(inject().getContentDescription().children[1].attributes['arkweb-node-id'], id);
    assert.notEqual(web.queryCss(selector)[0].attributes['arkweb-node-id'], id);
});

test('checks the actual physical tap point, including offsets and independent viewport scales', () => {
    const { inject, hits, button } = page();
    const web = inject();
    const match = web.queryCss(selector)[1].attributes;
    assert.equal(match.bounds, '[60,440][180,520]');
    assert.equal(web.arkWebTapHit(selector, match['arkweb-node-id'], 120, 480), 'hit'); // label inside the button
    hits.receiver = button;
    assert.equal(web.arkWebTapHit(selector, match['arkweb-node-id'], 90, 450), 'hit');
    assert.deepEqual(hits.points, [[30, 40], [20, 25]]);
});

test('overlays and another match under the pointer block the originally selected button', () => {
    const { inject, hits, overlay, sibling } = page();
    const web = inject();
    const id = web.queryCss(selector)[1].attributes['arkweb-node-id'];
    hits.receiver = overlay;
    assert.equal(web.arkWebTapHit(selector, id, 120, 480), 'blocked by div#cover');
    hits.receiver = sibling;
    assert.equal(web.arkWebTapHit(selector, id, 120, 480), 'blocked by button');
});

test('a replacement or a new page at the same location cannot inherit the target identity', () => {
    const { window, inject, hits } = page();
    const web = inject();
    const id = web.queryCss(selector)[1].attributes['arkweb-node-id'];
    hits.matches[1] = new Element('button');
    hits.receiver = hits.matches[1];
    assert.equal(web.arkWebTapHit(selector, id, 120, 480), 'target changed or no longer matches');
    delete window.maestro;
    const newPage = inject();
    newPage.queryCss(selector);
    assert.equal(newPage.arkWebTapHit(selector, id, 120, 480), 'target changed or no longer matches');
});

test('out-of-bounds points and missing receivers never pass the hit check', () => {
    const { inject, hits } = page();
    const web = inject();
    const id = web.queryCss(selector)[1].attributes['arkweb-node-id'];
    for (const [x, y] of [[29, 480], [630, 480], [120, 399], [120, 600], [NaN, 480]]) {
        assert.equal(web.arkWebTapHit(selector, id, x, y), 'tap point outside WebView');
    }
    assert.equal(hits.points.length, 0);
    hits.receiver = null;
    assert.equal(web.arkWebTapHit(selector, id, 120, 480), 'no element at tap point');
    assert.equal(web.arkWebTapHit(selector, '', 120, 480), 'target identity unavailable');
});
