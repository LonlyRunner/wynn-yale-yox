<script setup lang="ts">
import { ref } from 'vue'
export type JournalImage = { id: string; name: string; url: string; previewUrl: string }
const props = defineProps<{ images?: JournalImage[]; editable?: boolean; busy?: boolean }>()
const emit = defineEmits<{ remove: [image: JournalImage] }>()
const dialog = ref<HTMLDialogElement>(), selected = ref<JournalImage>()
function open(image: JournalImage) { selected.value = image; dialog.value?.showModal() }
function fallback(event: Event, image: JournalImage) {
  const target = event.target as HTMLImageElement
  if (target.dataset.fallback || !image.url) return
  target.dataset.fallback = '1'; target.src = image.url
}
</script>

<template>
  <div v-if="props.images?.length" class="journal-photos">
    <figure v-for="image in props.images" :key="image.id">
      <button type="button" class="photo" :aria-label="`放大查看：${image.name}`" @click="open(image)"><img :src="image.previewUrl" :alt="image.name" loading="lazy" decoding="async" @error="fallback($event,image)"></button>
      <button v-if="editable" type="button" class="remove" :disabled="busy" @click="emit('remove',image)">移除图片</button>
    </figure>
  </div>
  <dialog ref="dialog" class="journal-photo-dialog" @click.self="dialog?.close()">
    <button type="button" aria-label="关闭图片预览" @click="dialog?.close()">×</button>
    <img v-if="selected" :src="selected.url" :alt="selected.name">
    <p>{{selected?.name}}</p>
  </dialog>
</template>

<style scoped>
.journal-photos{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin-top:22px;min-width:0}
figure{margin:0;min-width:0}.photo{display:block;width:100%;padding:0;border:0;border-radius:18px;background:#f3e4da;overflow:hidden;aspect-ratio:4/3;cursor:zoom-in}.photo img{display:block;width:100%;height:100%;object-fit:cover}.remove{margin-top:8px;padding:8px 14px;border:1px solid #dfb9ab;border-radius:20px;background:#fff8f2;color:#9b5a4a;font:inherit;cursor:pointer}.remove:disabled{opacity:.5;cursor:wait}
.journal-photo-dialog{max-width:min(960px,94vw);max-height:90dvh;padding:20px;border:0;border-radius:22px;background:#fff8f2;color:#6b4941}.journal-photo-dialog::backdrop{background:#493329b3}.journal-photo-dialog>button{display:block;margin-left:auto;width:36px;height:36px;border:0;border-radius:50%;background:#f1d9cd;color:#6b4941;font-size:25px;cursor:pointer}.journal-photo-dialog img{display:block;max-width:100%;max-height:72dvh;margin:12px auto;object-fit:contain}.journal-photo-dialog p{margin:0;text-align:center;overflow-wrap:anywhere}
@media(max-width:760px){.journal-photos{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.journal-photo-dialog{padding:12px}}
</style>
