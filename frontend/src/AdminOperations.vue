<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ECharts } from 'echarts'
import { api } from './api'
import { siteServices } from './siteServices'
const props = defineProps<{ compact?: boolean }>()
type Count = { name: string; value: number }
type Traffic = { todayPv:number;todayUv:number;totalPv:number;totalUv:number;periodPv:number;periodUv:number;trend:Array<{day:string;pv:number;uv:number}>;breakdown:{pages:Count[];devices:Count[];sources:Count[]} }
const data = ref<Traffic>(), days = ref(30), loading = ref(false), saving = ref(false), notice = ref('')
const chartElement = ref<HTMLElement>()
let chart: ECharts | undefined, observer: ResizeObserver | undefined, disposed = false, version = 0
const names: Record<string,string> = { '/':'首页','/blog':'博客','/journal':'随心记','/gallery':'影像','/studio':'AI 创作','/guestbook':'留言板','/games':'游戏','/share':'分享','/about':'关于我', mobile:'手机',tablet:'平板',desktop:'电脑' }
async function load() {
  const current = ++version; loading.value = true; notice.value = ''
  const results = await Promise.allSettled([api('/admin/services'),api<Traffic>(`/admin/visitors?days=${days.value}`)])
  if (disposed || current !== version) return
  if (results[0].status === 'fulfilled') Object.assign(siteServices,results[0].value)
  if (results[1].status === 'fulfilled') { data.value = results[1].value; await draw() }
  if (results.some(item=>item.status==='rejected')) notice.value = '读取失败，请刷新重试'
  loading.value = false
}
async function toggle(key: 'aiCreationEnabled'|'catChatEnabled') {
  saving.value = true; notice.value = ''
  try { Object.assign(siteServices,await api('/admin/services',{method:'PUT',body:JSON.stringify({...siteServices,[key]:!siteServices[key]})})); notice.value = '开关已保存，即时生效' }
  catch { notice.value = '开关未保存，请重试' }
  finally { saving.value = false }
}
async function draw() {
  if (props.compact) return
  await nextTick(); const echarts = await import('echarts')
  if (disposed || !chartElement.value || !data.value) return
  chart ||= echarts.init(chartElement.value)
  chart.setOption({color:['#b77b69','#dcab79'],tooltip:{trigger:'axis'},legend:{bottom:0},grid:{left:38,right:18,top:22,bottom:58},xAxis:{type:'category',data:data.value.trend.map(item=>item.day.slice(5)),axisLabel:{color:'#92786d'}},yAxis:{type:'value',minInterval:1,axisLabel:{color:'#92786d'},splitLine:{lineStyle:{color:'#f0e0d6'}}},series:[{name:'浏览量 PV',type:'line',smooth:true,data:data.value.trend.map(item=>item.pv)},{name:'访客数 UV',type:'line',smooth:true,data:data.value.trend.map(item=>item.uv)}]})
  observer ||= new ResizeObserver(()=>chart?.resize()); observer.observe(chartElement.value)
}
onMounted(load); watch(days,load)
onBeforeUnmount(()=>{disposed=true;version++;observer?.disconnect();chart?.dispose()})
</script>

<template>
  <section class="operations" :class="{compact}">
    <div class="ops-heading"><h2><img src="/media/cat-items/paw.webp" alt="">服务与游客访问</h2><button type="button" :disabled="loading||saving" @click="load">{{loading?'读取中…':'刷新数据'}}</button></div>
    <div class="service-switches">
      <article><div><h3>AI 创作服务</h3><p>控制图片、视频的新生成任务，历史结果保留。</p></div><button type="button" role="switch" :aria-checked="siteServices.aiCreationEnabled" :disabled="saving||loading" :class="{off:!siteServices.aiCreationEnabled}" @click="toggle('aiCreationEnabled')"><i></i>{{siteServices.aiCreationEnabled?'已开启':'已关闭'}}</button></article>
      <article><div><h3>团子聊天</h3><p>控制文字和附件聊天，管理员历史记录保留。</p></div><button type="button" role="switch" :aria-checked="siteServices.catChatEnabled" :disabled="saving||loading" :class="{off:!siteServices.catChatEnabled}" @click="toggle('catChatEnabled')"><i></i>{{siteServices.catChatEnabled?'已开启':'已关闭'}}</button></article>
    </div>
    <p v-if="notice" role="status" class="ops-notice">{{notice}}</p>
    <template v-if="data">
      <div class="traffic-stats"><article><span>今日浏览量 PV</span><b>{{data.todayPv}}</b></article><article><span>今日访客 UV</span><b>{{data.todayUv}}</b></article><article><span>累计浏览量</span><b>{{data.totalPv}}</b></article><article><span>累计访客数</span><b>{{data.totalUv}}</b></article></div>
      <p class="traffic-note">PV 是页面浏览次数，UV 按匿名浏览器标识去重；同一人使用多个设备会计为多个访客。管理员访问不计入，数据从功能上线后累计。</p>
      <template v-if="!compact">
        <div class="traffic-heading"><h3>访问趋势</h3><label>统计范围<select v-model="days"><option :value="7">近 7 天</option><option :value="30">近 30 天</option><option :value="90">近 90 天</option></select></label></div>
        <p>本周期 {{data.periodPv}} 次浏览 · {{data.periodUv}} 位访客</p><div ref="chartElement" class="traffic-chart" aria-label="游客浏览量和访客数趋势图"></div>
        <p v-if="!data.totalPv" class="traffic-empty">还没有游客访问数据，公开页面被浏览后会自动更新。</p>
        <div class="traffic-breakdown"><article v-for="(label,key) in {pages:'热门页面',devices:'访问设备',sources:'来源网站'}" :key="key"><h3>{{label}}</h3><p v-if="!data.breakdown[key].length">暂无数据</p><ol v-else><li v-for="row in data.breakdown[key]" :key="row.name"><span :title="row.name">{{names[row.name]||row.name}}</span><b>{{row.value}}</b></li></ol></article></div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.operations{margin:24px 0;color:#634a41;min-width:0}.ops-heading,.traffic-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap}.ops-heading h2{display:flex;align-items:center;gap:10px;font-size:24px;margin:0}.ops-heading img{width:34px;height:34px;object-fit:contain}.operations button,.operations select{padding:10px 16px;border:1px solid #e3c4b5;border-radius:24px;font:inherit;background:#fffaf5;color:#715448;cursor:pointer;white-space:nowrap}.operations button:disabled{opacity:.6;cursor:wait}.service-switches{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin:18px 0}.service-switches article{display:flex;align-items:center;justify-content:space-between;gap:18px;border:1px solid #e8cdbc;border-radius:24px;background:#fffaf4;padding:22px;min-width:0}.operations h3{font-size:18px;margin:0 0 10px}.operations p{font-size:14px;line-height:1.7;margin:0;color:#9a7e71}.service-switches p{max-width:32ch}.service-switches button{display:flex;align-items:center;gap:8px;background:#b77b69;color:white}.service-switches button.off{background:#eee0d7;color:#886c60}.service-switches i{width:24px;height:14px;background:#ffffff75;border-radius:20px;position:relative}.service-switches i:after{content:'';position:absolute;width:10px;height:10px;border-radius:50%;background:white;top:2px;right:2px}.service-switches .off i:after{right:12px}.traffic-stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin:20px 0 12px}.traffic-stats article{border:1px solid #ead2c3;border-radius:24px;background:#fffaf4;padding:22px}.traffic-stats span{font-size:14px;color:#9a7e71}.traffic-stats b{display:block;font-size:36px;font-weight:500;margin-top:10px}.traffic-note{max-width:90ch}.traffic-heading{margin:24px 0 12px}.traffic-heading label{display:flex;align-items:center;gap:12px;font-size:14px}.traffic-chart{height:300px;min-width:0;width:100%;background:#fffaf4;border-radius:24px;margin:16px 0}.traffic-breakdown{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;margin-top:20px}.traffic-breakdown article{padding:22px;background:#fffaf4;border:1px solid #ead2c3;border-radius:24px;min-width:0}.traffic-breakdown ol{list-style:none;padding:0;margin:0}.traffic-breakdown li{display:flex;gap:12px;justify-content:space-between;align-items:center;padding:12px 0;border-bottom:1px solid #f1e3d9;font-size:14px}.traffic-breakdown li span{min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.ops-notice{margin:12px 0!important}.traffic-empty{padding:12px}.compact .ops-heading h2{font-size:21px}
@media(max-width:1000px){.service-switches,.traffic-breakdown{grid-template-columns:1fr}.traffic-stats{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:480px){.service-switches article{align-items:flex-start;flex-direction:column;padding:18px}.traffic-stats{gap:10px}.traffic-stats article{padding:16px}.traffic-stats b{font-size:30px}.traffic-chart{height:260px}}
</style>
