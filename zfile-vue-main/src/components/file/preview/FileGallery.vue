<template>
	<!-- 画廊模式 -->
	<div class="student-cloud-gallery-body">
		<div class="student-cloud-img-body" v-if="transferResult.length > 0">
			<div class="student-cloud-img-row" v-for="(rowItem, index) in transferResult" :key="index">
				<div class="student-cloud-img-col"
				     @click="openGalleryImage(colItem)"
				     :style="{ display: globalConfigStore.studentCloudConfig.gallery.showInfoMode === 'hover' ? 'flex' : 'block'}"
				     v-for="colItem in rowItem" :key="colItem.path + '/' + colItem.name">
					<template v-if="colItem?.url">
            <img class="student-cloud-gallery-img lazyload"
                 data-sizes="auto"
                 @load="loadImg"
                 :class="globalConfigStore.studentCloudConfig.gallery.roundedBorder ? 'student-cloud-gallery-img-rounded' : ''"
                 :data-src="colItem.url"
                 loading="lazy"
                 :alt="colItem.name"/>
            <div v-if="globalConfigStore.studentCloudConfig.gallery.showInfo &&
					    globalConfigStore.studentCloudConfig.gallery.showInfoMode === 'hover'"
                 :class="globalConfigStore.studentCloudConfig.gallery.roundedBorder ? 'student-cloud-gallery-img-rounded' : ''"
                 v-show="loadedList.includes(colItem.name)"
                 class="student-cloud-gallery-img-hover-info">
              <span class="student-cloud-gallery-img-text">{{colItem.name}}</span>
              <span class="student-cloud-gallery-img-text">{{fileSizeFormat(colItem.size)}}</span>
            </div>
            <div
              v-show="loadedList.includes(colItem.name)"
              v-if="globalConfigStore.studentCloudConfig.gallery.showInfo &&
						globalConfigStore.studentCloudConfig.gallery.showInfoMode === 'bottom'">
              <span class="student-cloud-gallery-img-text"> {{ colItem.name }} </span>
            </div>
          </template>
				</div>
			</div>
		</div>
    <div v-else class="h-full">
      <el-empty class="h-full" description="当前文件夹无图片">
        <el-button type="primary" @click="fileDataStore.imgMode = false">退出画廊模式</el-button>
      </el-empty>
    </div>
	</div>
</template>

<script setup>
import { fileSizeFormat } from "~/utils";
import {computed, reactive, ref} from "vue";


import 'lazysizes';
import useGlobalConfigStore from "~/stores/global-config";
let globalConfigStore = useGlobalConfigStore();

import useFileDataStore from "~/stores/file-data";
let fileDataStore = useFileDataStore();

// 计算图片二维列表, 多行 * 多列
const transferResult = computed(() => {
	// 获取图片列表
	let imgList = fileDataStore.filterFileByType('image');

  if (imgList.length === 0) {
    return [];
  }

	// 图片二维数组, 表示每行每列的图片
	let imgArray = ref([]);

	// 图片列数
	let galleryColumn = globalConfigStore.studentCloudConfig.gallery.column;

	// 当前行数
	let currRow = 0;

	imgList.forEach((item, index) => {
		if (index % galleryColumn === 0) {
			if (index !== 0) {
				currRow++;
			}
			imgArray.value[currRow] = [];
		}
		imgArray.value[currRow].push(item);
	})

	// 图片行转列函数
	function transfer(oldArr) {
		return oldArr[0].map((col, i) => oldArr.map(row => row[i]));
	}

	// 图片行转列完成后的数组
	return transfer(imgArray.value);
})

// 图片行间距 px
let galleryRowSpacingPx = computed(() => {
	return globalConfigStore.studentCloudConfig.gallery.rowSpacing + 'px';
});

// 图片列间距 px
let galleryColSpacingPx = computed(() => {
  return `0 ${globalConfigStore.studentCloudConfig.gallery.columnSpacing / 2}px`;
});

// 图片外部容器宽度, 100 / 图片列数
let galleryRowWidth = computed(() => {
  // 图片列数
  let galleryColumn = globalConfigStore.studentCloudConfig.gallery.column;
  let galleryColumnSpacing = globalConfigStore.studentCloudConfig.gallery.columnSpacing;
  return `calc(calc(100% / ${globalConfigStore.studentCloudConfig.gallery.column}) - calc(${(galleryColumn - 1) * galleryColumnSpacing}px / ${galleryColumn}))`;
})


// 引入文件预览组件
import useFilePreview from '~/composables/file/useFilePreview';
const { openImage } = useFilePreview();
const openGalleryImage = (item) => {
  if (!globalConfigStore.studentCloudConfig.imagePreview.gallery) {
    return;
  }
  if (loadedList.includes(item.name)) {
    openImage(item);
  }
}

// 已加载完的图片列表, 已加载完才悬浮显示标题
let loadedList = reactive([]);
const loadImg = (e) => {
  loadedList.push(e.currentTarget.alt);
}

</script>

<style lang="scss" scoped>

.student-cloud-gallery-body {
	height: 100%;
  margin-top: 10px;

	.student-cloud-img-body {
		@apply flex h-full flex-wrap;
	}

	.student-cloud-img-row {
		width: v-bind('galleryRowWidth');
    margin: v-bind('galleryColSpacingPx');

    &:first-child {
      margin-left: 0;
    }

    &:last-child {
      margin-right: 0;
    }

  }

	.student-cloud-img-col {
		@apply flex overflow-hidden relative text-center;
		margin-bottom: v-bind('galleryRowSpacingPx');

		.student-cloud-gallery-img {
			@apply border;
		}

    .student-cloud-gallery-img:not(.loaded) {
      @apply w-full;
    }

    .student-cloud-gallery-img:not(.lazyloaded) {
      @apply min-h-[150px];
    }

		.student-cloud-gallery-img-rounded {
			@apply rounded-lg;
		}

		.student-cloud-gallery-img-text {
			@apply overflow-ellipsis overflow-hidden whitespace-nowrap text-sm opacity-70;
		}

		.student-cloud-gallery-img-hover-info {
			@apply absolute top-0 h-1/2 left-0 right-0 text-sm p-2
			transition-opacity duration-300
			flex justify-between
			text-white space-x-10 opacity-0;
			background: linear-gradient(180deg,rgba(0,0,0,.6),transparent 120px);

			.student-cloud-gallery-img-text:last-child {
				@apply text-right min-w-fit;
			}
		}

		&:hover .student-cloud-gallery-img-hover-info {
			@apply opacity-100;
		}
	}

}

</style>
