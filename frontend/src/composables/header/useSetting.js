import { isMobile } from "~/utils";

const visible = ref(false);

// 获取默认值
import useGlobalConfigStore from "~/stores/global-config";
let globalConfigStore = useGlobalConfigStore();
let studentCloudConfig = globalConfigStore.studentCloudConfig;

let baseData = {
    view: {
        size: 2,
        type: 'table',
    },
    gallery: {
        column: isMobile.value ? studentCloudConfig.gallery.mobileColumn : studentCloudConfig.gallery.column,
        columnSpacing: studentCloudConfig.gallery.columnSpacing,
        rowSpacing: studentCloudConfig.gallery.rowSpacing,
        showInfo: studentCloudConfig.gallery.showInfo,
        showInfoMode: studentCloudConfig.gallery.showInfoMode,
        roundedBorder: studentCloudConfig.gallery.roundedBorder,
        showBackTop: studentCloudConfig.gallery.showBackTop
    },
    imagePreview: {
        mode: studentCloudConfig.imagePreview.mode,
        gallery: studentCloudConfig.imagePreview.gallery
    }
};

const extend = (a, b) => {
    b = b || this;

    for(let key in a) {
        if (b[key] === undefined) {
            b[key] = a[key];
        } else if (a[key] instanceof Object && b[key] instanceof Object) {
            extend(a[key], b[key]);
        }
    }
}

const studentCloudSettingCache = useStorage('student-cloud-setting-cache', baseData);
if (!studentCloudSettingCache.value.view.type) {
	studentCloudSettingCache.value.view.type = 'table';
}

export default function useSetting() {

    const openSettingVisible = () => {
        visible.value = true;
    }

    extend(baseData, studentCloudSettingCache.value)

    return {
        visible, studentCloudSettingCache, openSettingVisible
    }
}