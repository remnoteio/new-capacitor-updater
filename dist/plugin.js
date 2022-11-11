var capacitorCapacitorUpdater = (function (exports, core) {
    'use strict';

    const CapacitorUpdater = core.registerPlugin('CapacitorUpdater', {
        web: () => Promise.resolve().then(function () { return web; }).then((m) => new m.CapacitorUpdaterWeb()),
    });

    const BUNDLE_BUILTIN = {
        status: 'success',
        version: '',
        downloaded: '1970-01-01T00:00:00.000Z',
        id: 'builtin',
        checksum: '',
    };
    class CapacitorUpdaterWeb extends core.WebPlugin {
        async download(options) {
            console.warn('Cannot download version in web', options);
            return BUNDLE_BUILTIN;
        }
        async next(options) {
            console.warn('Cannot set next version in web', options);
            return BUNDLE_BUILTIN;
        }
        async isAutoUpdateEnabled() {
            console.warn('Cannot get isAutoUpdateEnabled in web');
            return { enabled: false };
        }
        async set(options) {
            console.warn('Cannot set active bundle in web', options);
            return;
        }
        async getDeviceId() {
            console.warn('Cannot get ID in web');
            return { deviceId: 'default' };
        }
        async getPluginVersion() {
            console.warn('Cannot get plugin version in web');
            return { version: 'default' };
        }
        async delete(options) {
            console.warn('Cannot delete bundle in web', options);
        }
        async list() {
            console.warn('Cannot list bundles in web');
            return { bundles: [] };
        }
        async reset(options) {
            console.warn('Cannot reset version in web', options);
        }
        async current() {
            console.warn('Cannot get current bundle in web');
            return { bundle: BUNDLE_BUILTIN, native: '0.0.0' };
        }
        async reload() {
            console.warn('Cannot reload current bundle in web');
            return;
        }
        async getLatest() {
            console.warn('Cannot getLatest current bundle in web');
            return {
                version: '0.0.0',
                message: 'Cannot getLatest current bundle in web',
            };
        }
        async setChannel(options) {
            console.warn('Cannot setChannel in web', options);
            return {
                status: 'error',
                error: 'Cannot setChannel in web',
            };
        }
        async setCustomId(options) {
            console.warn('Cannot setCustomId in web', options);
            return;
        }
        async getChannel() {
            console.warn('Cannot getChannel in web');
            return {
                status: 'error',
                error: 'Cannot getChannel in web',
            };
        }
        async notifyAppReady() {
            console.warn('Cannot notify App Ready in web');
            return BUNDLE_BUILTIN;
        }
        async setMultiDelay(options) {
            console.warn('Cannot setMultiDelay in web', options === null || options === void 0 ? void 0 : options.delayConditions);
            return;
        }
        async setDelay(option) {
            console.warn('Cannot setDelay in web', option);
            return;
        }
        async cancelDelay() {
            console.warn('Cannot cancelDelay in web');
            return;
        }
    }

    var web = /*#__PURE__*/Object.freeze({
        __proto__: null,
        CapacitorUpdaterWeb: CapacitorUpdaterWeb
    });

    exports.CapacitorUpdater = CapacitorUpdater;

    Object.defineProperty(exports, '__esModule', { value: true });

    return exports;

})({}, capacitorExports);
//# sourceMappingURL=plugin.js.map
