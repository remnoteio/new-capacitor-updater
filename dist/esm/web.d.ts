import { WebPlugin } from '@capacitor/core';
import type { CapacitorUpdaterPlugin, BundleInfo, latestVersion, DelayCondition, channelRes, SetChannelOptions, getChannelRes, SetCustomIdOptions } from './definitions';
export declare class CapacitorUpdaterWeb extends WebPlugin implements CapacitorUpdaterPlugin {
    download(options: {
        url: string;
        version?: string;
    }): Promise<BundleInfo>;
    next(options: {
        id: string;
    }): Promise<BundleInfo>;
    isAutoUpdateEnabled(): Promise<{
        enabled: boolean;
    }>;
    set(options: {
        id: string;
    }): Promise<void>;
    getDeviceId(): Promise<{
        deviceId: string;
    }>;
    getPluginVersion(): Promise<{
        version: string;
    }>;
    delete(options: {
        id: string;
    }): Promise<void>;
    list(): Promise<{
        bundles: BundleInfo[];
    }>;
    reset(options?: {
        toLastSuccessful?: boolean;
    }): Promise<void>;
    current(): Promise<{
        bundle: BundleInfo;
        native: string;
    }>;
    reload(): Promise<void>;
    getLatest(): Promise<latestVersion>;
    setChannel(options: SetChannelOptions): Promise<channelRes>;
    setCustomId(options: SetCustomIdOptions): Promise<void>;
    getChannel(): Promise<getChannelRes>;
    notifyAppReady(): Promise<BundleInfo>;
    setMultiDelay(options: {
        delayConditions: DelayCondition[];
    }): Promise<void>;
    setDelay(option: DelayCondition): Promise<void>;
    cancelDelay(): Promise<void>;
}
