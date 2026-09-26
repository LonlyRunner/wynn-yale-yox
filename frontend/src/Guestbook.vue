<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { api, ApiError } from './api'

const props=withDefaults(defineProps<{lang:'zh'|'en';admin?:boolean}>(),{admin:false})
const text=(zh:string,en:string)=>props.lang==='zh'?zh:en
type Attachment={name:string;size:number;image:boolean;url:string;downloadUrl:string;failed?:boolean}
type Entry={id:number;author:string;content:string;contact?:string;createdAt:string;attachments:Attachment[]}
type EntryPage={items:Entry[];page:number;hasMore:boolean;total:number}
const entries=ref<Entry[]>([]),pageIndex=ref(0),total=ref(0),hasMore=ref(false),loading=ref(false),listError=ref('')
const author=ref(''),contact=ref(''),content=ref(''),submitting=ref(false),progress=ref(0),submitError=ref(''),success=ref('')
const files=ref<Array<{file:File;preview:string}>>([]),fileInput=ref<HTMLInputElement>()
const deleteId=ref<number>(),deleting=ref<number>(),preview=ref<Attachment>(),previewDialog=ref<HTMLDialogElement>()
const canSubmit=computed(()=>!!content.value.trim()||files.value.length>0)
let activeUpload:XMLHttpRequest|undefined
function errorText(error:unknown){
  if(error instanceof ApiError){try{const body=JSON.parse(error.message);return body.detail||body.message||text('操作失败，请稍后重试。','Request failed. Please retry.')}catch{}}
  return error instanceof Error?error.message:text('操作失败，请稍后重试。','Request failed. Please retry.')
}
async function loadEntries(page=pageIndex.value){
  loading.value=true;listError.value=''
  try{const result=await api<EntryPage>(`/${props.admin?'admin':'public'}/guestbook?page=${page}`);entries.value=result.items;pageIndex.value=result.page;total.value=result.total;hasMore.value=result.hasMore}
  catch(error){listError.value=errorText(error)}finally{loading.value=false}
}
function fileSize(size:number){return size>=1024*1024?`${(size/1024/1024).toFixed(1)} MB`:`${Math.max(1,Math.round(size/1024))} KB`}
function selectFiles(event:Event){
  const input=event.target as HTMLInputElement,added=Array.from(input.files||[]);input.value='';submitError.value='';success.value=''
  if(files.value.length+added.length>4){submitError.value=text('每条留言最多添加 4 个附件。','Attach up to 4 files per message.');return}
  if(added.some(file=>!file.size||file.size>5*1024*1024)){submitError.value=text('附件不能为空，且每个不能超过 5 MB。','Files must be nonempty and at most 5 MB each.');return}
  if([...files.value.map(item=>item.file),...added].reduce((sum,file)=>sum+file.size,0)>16*1024*1024){submitError.value=text('附件总大小不能超过 16 MB。','Attachments cannot exceed 16 MB in total.');return}
  files.value.push(...added.map(file=>({file,preview:['image/jpeg','image/png','image/gif','image/webp'].includes(file.type)?URL.createObjectURL(file):''})))
}
function removeFile(index:number){const [item]=files.value.splice(index,1);if(item?.preview)URL.revokeObjectURL(item.preview)}
async function submit(){
  if(submitting.value||!canSubmit.value)return
  submitting.value=true;progress.value=0;submitError.value='';success.value=''
  const form=new FormData();form.append('author',author.value.trim());form.append('contact',contact.value.trim());form.append('content',content.value.trim());files.value.forEach(item=>form.append('files',item.file))
  try{
    await new Promise<void>((resolve,reject)=>{
      const xhr=new XMLHttpRequest();activeUpload=xhr;xhr.open('POST','/api/public/guestbook');xhr.withCredentials=true;xhr.timeout=120000
      xhr.upload.onprogress=event=>{if(event.lengthComputable)progress.value=Math.round(event.loaded/event.total*100)}
      xhr.onload=()=>{if(xhr.status>=200&&xhr.status<300){resolve();return}if(xhr.status===413){reject(new Error(text('附件太大，请减少大小后重试。','Attachments are too large.')));return}reject(new ApiError(xhr.status,xhr.responseText))}
      xhr.onerror=xhr.ontimeout=()=>reject(new Error(text('连接中断，请先刷新留言确认是否已发布。','Connection interrupted. Refresh messages before retrying.')))
      xhr.onabort=()=>reject(new Error(text('上传已取消','Upload cancelled')));xhr.send(form)
    })
    content.value='';contact.value='';while(files.value.length)removeFile(0)
    success.value=text('留言已送达，谢谢你来留下足迹！','Your message is here. Thanks for stopping by!')
    await loadEntries(0)
  }catch(error){submitError.value=errorText(error)}finally{submitting.value=false;activeUpload=undefined}
}
async function removeEntry(id:number){
  if(deleting.value)return
  deleting.value=id;listError.value=''
  try{await api(`/admin/guestbook/${id}`,{method:'DELETE'});deleteId.value=undefined;await loadEntries();if(!entries.value.length&&pageIndex.value>0)await loadEntries(pageIndex.value-1)}
  catch(error){listError.value=errorText(error)}finally{deleting.value=undefined}
}
async function openPreview(file:Attachment){preview.value=file;await nextTick();previewDialog.value?.showModal()}
onMounted(()=>loadEntries(0))
onBeforeUnmount(()=>{activeUpload?.abort();files.value.forEach(item=>{if(item.preview)URL.revokeObjectURL(item.preview)})})
</script>

<template>
  <section :class="['guestbook-page',{'guestbook-admin':admin}]">
    <header class="guestbook-heading"><div><small>{{admin?'GUESTBOOK MANAGEMENT':'LEAVE A LITTLE PAWPRINT'}}</small><h1>{{text(admin?'游客留言':'留言板',admin?'Visitor messages':'Guestbook')}}</h1><p>{{text(admin?'查看游客的留言与联系方式，删除留言时也会清理它的附件。':'打个招呼、分享今天的小事，或者留下你想告诉我的话。',admin?'Read visitor messages and private contact details. Deleting a message also removes its attachments.':'Say hello, share a little moment, or leave something you would like me to know.')}}</p></div><img src="/media/fluffy-kitten.webp" alt="" aria-hidden="true"></header>
    <div v-if="!admin" class="guestbook-contact"><img src="/media/cat-items/paw.webp" alt=""><p>{{text('如果需要回复或反馈，请留下联系方式，也可以直接联系站主。','If you need a reply, leave your contact details or contact the site owner.')}} <RouterLink to="/about">{{text('联系站主 · QQ 3069226178 →','Contact owner · QQ 3069226178 →')}}</RouterLink></p></div>
    <div class="guestbook-layout">
      <form v-if="!admin" class="guestbook-form" @submit.prevent="submit">
        <h2>{{text('留一张小纸条','Leave a little note')}}</h2>
        <label>{{text('昵称（选填）','Name (optional)')}}<input v-model="author" maxlength="80" :disabled="submitting" :placeholder="text('路过的小伙伴','A passing friend')" autocomplete="nickname"></label>
        <label>{{text('联系方式（选填，仅站主可见）','Contact (optional, visible only to the owner)')}}<input v-model="contact" maxlength="200" :disabled="submitting" :placeholder="text('QQ / 微信 / 邮箱，方便回复你','QQ / WeChat / email for a reply')"></label>
        <label>{{text('想说的话','Your message')}}<textarea v-model="content" rows="6" maxlength="3000" :disabled="submitting" :placeholder="text('文字、图片或文件，都可以留在这里…','Leave a message, photo, or file…')"></textarea></label>
        <div class="guestbook-input-meta"><span>{{content.length}} / 3000</span><button type="button" :disabled="submitting||files.length>=4" @click="fileInput?.click()">＋ {{text('添加图片 / 文件','Add images / files')}}</button><input ref="fileInput" class="guestbook-file-input" type="file" multiple :disabled="submitting" @change="selectFiles"></div>
        <ul v-if="files.length" class="guestbook-selected"><li v-for="(item,index) in files" :key="index"><img v-if="item.preview" :src="item.preview" alt=""><span><b>{{item.file.name}}</b><small>{{fileSize(item.file.size)}}</small></span><button type="button" :disabled="submitting" :aria-label="text('移除附件 ','Remove attachment ')+item.file.name" @click="removeFile(index)">×</button></li></ul>
        <p class="guestbook-hint">{{text('最多 4 个附件，单个 5 MB，总计 16 MB。留言内容及附件将公开展示；联系方式请填在上面的专用栏。','Up to 4 files, 5 MB each, 16 MB total. Messages and attachments are public; use the private field above for contact details.')}}</p>
        <div v-if="submitting" class="guestbook-progress"><progress max="100" :value="progress" :aria-label="text('上传进度','Upload progress')"></progress><small>{{progress<100?text(`上传中 ${progress}%`,`Uploading ${progress}%`):text('正在保存留言…','Saving your message…')}}</small></div>
        <button class="guestbook-submit" :disabled="submitting||!canSubmit">{{submitting?text('发送中…','Sending…'):text('留下足迹','Leave a pawprint')}} <img src="/media/cat-items/paw.webp" alt=""></button>
        <p v-if="submitError" class="guestbook-error" role="alert">{{submitError}}</p><p v-if="success" class="guestbook-success" role="status">{{success}}</p>
      </form>
      <div class="guestbook-messages" :aria-busy="loading">
        <div class="guestbook-list-heading"><h2>{{text('大家的留言','Little notes')}} <small>{{total}}</small></h2><button type="button" :disabled="loading||submitting||!!deleting" @click="loadEntries()">{{text('刷新留言','Refresh')}}</button></div>
        <p v-if="listError" class="guestbook-error" role="alert">{{listError}}</p>
        <div v-else-if="loading&&!entries.length" class="guestbook-empty" role="status">{{text('正在收集小纸条…','Gathering little notes…')}}</div>
        <div v-else-if="!entries.length" class="guestbook-empty"><img src="/media/cat-items/feather.webp" alt=""><h3>{{text('第一张小纸条，等你来写','Be the first to leave a note')}}</h3><p>{{text('不需要登录，来打个招呼吧。','No account needed. Come say hello.')}}</p></div>
        <article v-for="item in entries" :key="item.id" class="guestbook-entry">
          <header><img src="/media/cat-items/paw.webp" alt=""><div><h3>{{item.author}}</h3><time :datetime="item.createdAt">{{new Date(item.createdAt).toLocaleString(lang==='zh'?'zh-CN':'en-US')}}</time></div></header>
          <p v-if="item.content" class="guestbook-content">{{item.content}}</p>
          <div v-if="item.attachments.length" class="guestbook-attachments"><div v-for="(file,index) in item.attachments" :key="index" class="guestbook-attachment"><button v-if="file.image&&file.url" type="button" class="guestbook-image" :aria-label="text('预览图片 ','Preview ')+file.name" @click="openPreview(file)"><img v-if="!file.failed" :src="file.url" :alt="file.name" loading="lazy" decoding="async" @error="file.failed=true"><span v-else>{{text('预览暂不可用，请刷新留言或下载','Preview unavailable. Refresh or download.')}}</span></button><a v-if="file.downloadUrl" :href="file.downloadUrl" download rel="noopener noreferrer"><span>↓ {{file.name}}</span><small>{{fileSize(file.size)}}</small></a><p v-else>{{file.name}} · {{text('附件暂不可用','Attachment unavailable')}}</p></div></div>
          <p v-if="admin" class="guestbook-private">{{text('联系方式：','Contact: ')}}{{item.contact||text('未填写','Not provided')}}</p>
          <footer v-if="admin" class="guestbook-delete"><template v-if="deleteId===item.id"><span>{{text('删除这条留言和全部附件？','Delete this note and all attachments?')}}</span><button type="button" :disabled="!!deleting" @click="removeEntry(item.id)">{{deleting===item.id?text('删除中…','Deleting…'):text('确认删除','Delete permanently')}}</button><button type="button" :disabled="!!deleting" @click="deleteId=undefined">{{text('取消','Cancel')}}</button></template><button v-else type="button" :disabled="!!deleting" @click="deleteId=item.id">{{text('删除留言','Delete note')}}</button></footer>
        </article>
        <nav v-if="pageIndex>0||hasMore" class="guestbook-pagination" :aria-label="text('留言分页','Message pages')"><button type="button" :disabled="loading||pageIndex===0" @click="loadEntries(pageIndex-1)">{{text('上一页','Previous')}}</button><span>{{pageIndex+1}}</span><button type="button" :disabled="loading||!hasMore" @click="loadEntries(pageIndex+1)">{{text('下一页','Next')}}</button></nav>
      </div>
    </div>
    <dialog ref="previewDialog" class="guestbook-preview" :aria-label="text('图片预览','Image preview')" @click.self="previewDialog?.close()"><button type="button" class="guestbook-preview-close" :aria-label="text('关闭预览','Close preview')" @click="previewDialog?.close()">×</button><figure v-if="preview"><img v-if="!preview.failed" :src="preview.url" :alt="preview.name" @error="preview.failed=true"><p v-else>{{text('图片加载失败，请刷新留言后重试。','Image failed to load. Refresh the messages and retry.')}}</p><figcaption>{{preview.name}} <a :href="preview.downloadUrl" download>{{text('下载图片','Download')}}</a></figcaption></figure></dialog>
  </section>
</template>

<style scoped>
.guestbook-page{max-width:1280px;margin:auto;padding:60px 5vw 90px;color:#60453d}.guestbook-heading{display:flex;align-items:center;justify-content:space-between;gap:24px;margin-bottom:28px}.guestbook-heading>div{min-width:0}.guestbook-heading small{color:#b67868;font-size:11px;letter-spacing:.22em}.guestbook-heading h1{font:clamp(38px,5vw,66px)/1.15 var(--serif);margin:18px 0}.guestbook-heading p{color:#92766b;line-height:1.8;margin:0;max-width:660px}.guestbook-heading>img{width:120px;flex-shrink:0;filter:drop-shadow(0 12px 14px #b9836d25)}
.guestbook-contact{display:flex;align-items:center;gap:18px;background:#f9e7da;border:1px solid #efd2c2;border-radius:22px;padding:17px 24px;margin-bottom:28px}.guestbook-contact>img{width:46px;flex-shrink:0}.guestbook-contact p{margin:0;line-height:1.8;font-size:14px}.guestbook-contact a{display:inline-block;color:#a45f4e;text-decoration:underline;text-underline-offset:4px}.guestbook-layout{display:grid;grid-template-columns:minmax(0,.9fr) minmax(0,1.1fr);gap:30px;align-items:start}.guestbook-page h2{font:26px/1.3 var(--serif);margin:0 0 22px}.guestbook-form{min-width:0;padding:28px;background:#fffaf4;border:1px solid #e8cdbd;border-radius:28px;box-shadow:0 14px 40px #8558430b}.guestbook-form label{display:grid;gap:9px;margin:18px 0;font-size:13px;color:#8b6a5e}.guestbook-form input:not([type=file]),.guestbook-form textarea{width:100%;min-width:0;padding:12px 14px;border:1px solid #e5cbbd;border-radius:13px;background:#fffdf9;color:#60453d;font:inherit;font-size:14px;line-height:1.6}.guestbook-form textarea{resize:vertical;min-height:150px;max-height:400px;font:inherit;line-height:1.8}.guestbook-page button{cursor:pointer;font:inherit}.guestbook-page button:disabled{opacity:.5;cursor:not-allowed}.guestbook-page :is(button,input,textarea,a):focus-visible{outline:3px solid #cd9b83;outline-offset:3px}.guestbook-input-meta{display:flex;flex-wrap:wrap;gap:12px;align-items:center;justify-content:space-between}.guestbook-input-meta>span{color:#ad8c7e;font-size:11px}.guestbook-input-meta button,.guestbook-list-heading button,.guestbook-pagination button{border:1px solid #e3c5b5;border-radius:999px;padding:8px 14px;color:#965f4e;background:#fff9f2;font-size:12px}.guestbook-file-input{display:none}.guestbook-hint{color:#a18273;font-size:12px;line-height:1.8;margin:20px 0}.guestbook-selected{list-style:none;margin:16px 0 0;padding:0}.guestbook-selected li{display:flex;align-items:center;gap:10px;padding:10px 0;border-bottom:1px dashed #e8d4c7}.guestbook-selected img{width:45px;height:45px;border-radius:9px;object-fit:cover}.guestbook-selected span{flex:1;min-width:0}.guestbook-selected b{display:block;font-size:12px;overflow-wrap:anywhere}.guestbook-selected small{color:#a18273;font-size:11px}.guestbook-selected button{border:0;background:#f8e9df;border-radius:50%;width:30px;height:30px;color:#975b4a;flex-shrink:0}.guestbook-progress{display:grid;gap:6px;font-size:12px;color:#a56a58;margin:16px 0}.guestbook-progress progress{width:100%;height:8px;accent-color:#b87b68}.guestbook-submit{display:flex;align-items:center;justify-content:center;gap:8px;width:100%;border:0;border-radius:999px;background:#b97d6a;color:#fffaf3;padding:10px 22px;min-height:48px}.guestbook-submit>img{width:28px;height:28px;object-fit:contain}.guestbook-error,.guestbook-success{font-size:13px;line-height:1.7;padding:12px 14px;border-radius:12px;overflow-wrap:anywhere}.guestbook-error{color:#ae5045;background:#ffebe5}.guestbook-success{color:#617b56;background:#edf2e7}.guestbook-messages{min-width:0}.guestbook-list-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:4px 0 18px}.guestbook-list-heading h2{margin:0}.guestbook-list-heading small{font:13px sans-serif;color:#b38371}.guestbook-entry{padding:24px;margin-bottom:18px;background:linear-gradient(135deg,#fffaf3,#fff3e8);border:1px solid #ead0c0;border-radius:25px;min-width:0;box-shadow:0 10px 25px #85584307}.guestbook-entry>header{display:flex;align-items:center;gap:12px}.guestbook-entry>header>img{width:35px;height:35px;object-fit:contain}.guestbook-entry>header>div{min-width:0}.guestbook-entry h3{margin:0 0 4px;font-size:15px;overflow-wrap:anywhere}.guestbook-entry time{font-size:10px;color:#ab897b}.guestbook-content{font-size:14px;line-height:1.9;white-space:pre-wrap;overflow-wrap:anywhere;margin:18px 0 0}.guestbook-attachments{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;margin-top:18px}.guestbook-attachment{border:1px solid #efd8ca;border-radius:14px;overflow:hidden;background:#fffbf6;min-width:0}.guestbook-image{display:block;border:0;background:#f6e6d9;width:100%;padding:0;min-height:100px}.guestbook-image img{display:block;width:100%;height:155px;object-fit:cover}.guestbook-image span{display:block;padding:15px;font-size:12px;line-height:1.6}.guestbook-attachment>a{display:flex;flex-direction:column;gap:6px;padding:12px;font-size:12px;overflow-wrap:anywhere;color:#a16450}.guestbook-attachment small{font-size:10px;color:#aa8a7a}.guestbook-attachment>p{padding:12px;font-size:12px;overflow-wrap:anywhere}.guestbook-private{border-top:1px dashed #e6cbbd;padding-top:14px;color:#996951;font-size:13px;overflow-wrap:anywhere}.guestbook-delete{display:flex;flex-wrap:wrap;gap:10px;align-items:center;margin-top:16px;font-size:12px}.guestbook-delete span{flex-basis:100%}.guestbook-delete button{border:1px solid #e2b9a6;background:#fff5ee;color:#a75e4b;border-radius:999px;padding:8px 14px;white-space:nowrap}.guestbook-empty{display:grid;justify-items:center;text-align:center;min-height:240px;align-content:center;border:1px dashed #e6c7b5;border-radius:28px;padding:30px;color:#a28577}.guestbook-empty img{width:65px;height:65px;object-fit:contain;margin-bottom:16px}.guestbook-empty h3{color:#97735f;font:23px var(--serif);margin:5px}.guestbook-empty p{font-size:13px}.guestbook-pagination{display:flex;align-items:center;justify-content:center;gap:20px;margin-top:24px;font-size:13px}.guestbook-preview{border:0;background:#fff9f1;color:#694c40;padding:22px;border-radius:22px;max-width:92vw;max-height:92dvh}.guestbook-preview::backdrop{background:#362419bb}.guestbook-preview figure{margin:18px 0 0}.guestbook-preview figure>img{display:block;max-width:82vw;max-height:72dvh;object-fit:contain}.guestbook-preview figcaption{margin-top:14px;overflow-wrap:anywhere;font-size:13px}.guestbook-preview a{color:#a0604e;text-decoration:underline;margin-left:12px}.guestbook-preview-close{position:absolute;right:10px;top:6px;border:0;background:#fff4e9;color:#905943;border-radius:50%;font-size:24px!important;width:34px;height:34px}.guestbook-admin{padding:20px 0 35px;max-width:none}.guestbook-admin .guestbook-layout{display:block}.guestbook-admin .guestbook-heading>img{width:70px;animation:none}.guestbook-admin .guestbook-heading h1{font-size:36px}.guestbook-admin .guestbook-attachments{grid-template-columns:repeat(auto-fit,minmax(150px,240px))}
@media(max-width:960px){.guestbook-layout{grid-template-columns:minmax(0,1fr)}.guestbook-heading>img{width:95px}}
@media(max-width:600px){.guestbook-page{padding:38px 18px 60px}.guestbook-heading{gap:8px}.guestbook-heading>img{width:75px}.guestbook-heading small{font-size:9px;letter-spacing:.1em}.guestbook-heading p{font-size:13px}.guestbook-contact{padding:16px;gap:12px}.guestbook-contact p{font-size:12px}.guestbook-contact>img{width:34px}.guestbook-form,.guestbook-entry{padding:20px}.guestbook-list-heading h2{font-size:23px}.guestbook-image img{height:125px}.guestbook-admin{padding:10px 0}.guestbook-admin .guestbook-attachments{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
