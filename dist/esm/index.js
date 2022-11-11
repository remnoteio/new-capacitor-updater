import { registerPlugin } from '@capacitor/core';
const CapacitorUpdater = registerPlugin('CapacitorUpdater', {
    web: () => import('./web').then((m) => new m.CapacitorUpdaterWeb()),
});
export * from './definitions';
export { CapacitorUpdater };
//# sourceMappingURL=index.js.map