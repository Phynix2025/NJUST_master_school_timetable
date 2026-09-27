const times={1:'08:00–08:45',2:'08:50–09:35',3:'09:40–10:25',4:'10:40–11:25',5:'11:30–12:15',6:'14:00–14:45',7:'14:50–15:35',8:'15:50–16:35',9:'16:40–17:25',10:'17:30–18:15',11:'19:00–19:45',12:'19:50–20:35',13:'20:40–21:25'};
const weekdays=['星期日','星期一','星期二','星期三','星期四','星期五','星期六'];
let displayedMonday=monday(new Date());
let suppressCourseClickUntil=0;
let schedule=null;
let renderedCourseCards=[];
const courseDetailsCache=new Map();
let activeCourseDetail=null;
let noteSaveTimer=null;
const imagePreviewState={active:false,scale:1,x:0,y:0,pointers:new Map(),gesture:null,moved:false};

function esc(value){return String(value||'').replace(/[&<>"']/g,character=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]))}
function escAttr(value){return esc(value).replace(/`/g,'&#96;')}
function dateAt(date,offset){const result=new Date(date);result.setDate(result.getDate()+offset);return result}
function monday(date){const result=new Date(date.getFullYear(),date.getMonth(),date.getDate());result.setDate(result.getDate()-(result.getDay()+6)%7);return result}
function fmt(date){return `${date.getMonth()+1}月${date.getDate()}日`}
function minute(value){const parts=value.split(':').map(Number);return parts[0]*60+parts[1]}
function weekNo(date){
  if(!schedule)return 0;
  if(schedule.termWeek1){const start=monday(new Date(`${schedule.termWeek1}T12:00:00`));return Math.floor((monday(date)-start)/604800000)+1}
  if(!schedule.syncWeek)return 0;
  return schedule.syncWeek+Math.round((monday(date)-monday(new Date(schedule.syncedAt||Date.now())))/604800000);
}
function inWeeks(spec,week){
  if(!week||!spec.trim())return true;
  return (spec.match(/\d+(?:\s*[-–]\s*\d+)?/g)||[]).some(part=>{
    const bounds=part.split(/[-–]/).map(value=>Number(value.trim()));
    return bounds.length===1?bounds[0]===week:week>=bounds[0]&&week<=bounds[1];
  });
}
function parseCourseText(text,slot){
  const courses=[];
  const expression=/([^；;｛{]+)[｛{]([\s\S]*?)[｝}]/g;
  let match;
  while((match=expression.exec(text))){
    const body=match[2];
    const weekMatch=body.match(/(.+?)周/);
    const teacher=(body.match(/教师\s*[:：]\s*([^,，\]\n]+)/)||[])[1]||'';
    const place=(body.match(/地点\s*[:：]\s*([^\]\n]+)/)||[])[1]||'';
    courses.push({name:match[1].trim(),weeks:weekMatch?weekMatch[1].trim():'',teacher:teacher.trim(),place:place.trim(),section:slot.section,weekday:slot.weekday});
  }
  return courses.length?courses:[{name:text.trim(),weeks:'',teacher:'',place:'',section:slot.section,weekday:slot.weekday}];
}
function mergeCourses(courses){
  const merged=[];
  courses.forEach(course=>{
    const previous=merged.find(item=>item.name===course.name&&item.teacher===course.teacher&&item.place===course.place&&item.weeks===course.weeks&&item.sectionEnd===course.section-1);
    if(previous)previous.sectionEnd=course.section;
    else merged.push({...course,sectionEnd:course.section});
  });
  return merged.sort((a,b)=>a.section-b.section);
}
function coursesOn(date){
  if(!schedule)return[];
  const week=weekNo(date),day=weekdays[date.getDay()];
  return mergeCourses(schedule.slots.filter(slot=>slot.weekday===day).flatMap(slot=>parseCourseText(slot.text,slot)).filter(course=>inWeeks(course.weeks,week)).sort((a,b)=>a.section-b.section));
}
function periodTime(course){return `${times[course.section].split('–')[0]}–${times[course.sectionEnd].split('–')[1]}`}
function stateOf(course,date){
  if(date.toDateString()!==new Date().toDateString())return'';
  const now=new Date(),clock=now.getHours()*60+now.getMinutes();
  const start=minute(times[course.section].split('–')[0]),end=minute(times[course.sectionEnd].split('–')[1]);
  return clock>=end?'finished':clock>=start?'live':'';
}
function weekStateOf(course,date){
  const today=new Date(),day=new Date(date.getFullYear(),date.getMonth(),date.getDate()),nowDay=new Date(today.getFullYear(),today.getMonth(),today.getDate());
  if(day<nowDay)return'finished';
  if(day>nowDay)return'';
  return stateOf(course,date);
}
function sectionLabel(course){return course.section===course.sectionEnd?`第 ${course.section} 节`:`第 ${course.section}–${course.sectionEnd} 节`}
function courseKey(course){return JSON.stringify([String(course.name||'').trim(),String(course.teacher||'').trim()])}
function readCourseDetails(course){
  const key=courseKey(course);
  if(courseDetailsCache.has(key))return courseDetailsCache.get(key);
  let details={text:'',images:[]};
  try{details=JSON.parse(Android.getCourseDetails(key)||'{}')}catch(_){}
  details={text:String(details.text||''),images:Array.isArray(details.images)?details.images.filter(uri=>String(uri).startsWith('content://')):[]};
  courseDetailsCache.set(key,details);return details;
}
function nextCourseToday(){
  const now=new Date(),clock=now.getHours()*60+now.getMinutes();
  const course=coursesOn(now).find(item=>minute(times[item.section].split('–')[0])>clock);
  return course||null;
}
// Course identity and detail storage remain shared across all weeks.
function courseColor(course){
  let hash=0;for(const character of courseKey(course))hash=(hash*31+character.charCodeAt(0))|0;
  return Math.abs(hash)%6;
}
function changeWeek(offset){displayedMonday=dateAt(displayedMonday,offset*7);render()}
function render(){
  renderedCourseCards=[];
  const now=new Date(),start=displayedMonday,current=monday(now),number=weekNo(start);
  document.getElementById('currentWeek').disabled=start.getTime()===current.getTime();
  const select=document.getElementById('weekSelect');
  const currentNumber=weekNo(current),first=Math.min(1,number||1),last=Math.max(25,number,currentNumber);
  select.innerHTML=schedule&&number?Array.from({length:last-first+1},(_,i)=>{
    const n=first+i;return `<option value="${n}" ${n===number?'selected':''}>第 ${n} 周</option>`;
  }).join(''):`<option>${fmt(start)}起</option>`;
  select.disabled=!schedule||!number;
  const end=dateAt(start,6),month=start.getMonth()===end.getMonth()?`${start.getMonth()+1}月`:`${start.getMonth()+1}/${end.getMonth()+1}月`;
  document.getElementById('weekDates').innerHTML=`<div class="month-label">${month}</div>`+Array.from({length:7},(_,i)=>{
    const date=dateAt(start,i);return `<div class="date-label ${date.toDateString()===now.toDateString()?'is-today':''}"><span>${['一','二','三','四','五','六','日'][i]}</span><b>${date.getDate()}</b></div>`;
  }).join('');
  const notice=document.getElementById('scheduleNotice');
  if(!schedule){notice.innerHTML='<div class="notice">还没有课程表，点击“同步课表”开始使用。</div>';document.getElementById('list').innerHTML='';return}
  let html=Object.entries(times).map(([section,time])=>`<div class="period-label" style="grid-row:${section};grid-column:1"><b>${section}</b><span>${time.split('–').join('<br>')}</span></div><div class="period-line" style="grid-row:${section};grid-column:2 / 9"></div>`).join('');
  let total=0;
  for(let day=0;day<7;day++){
    const date=dateAt(start,day),courses=coursesOn(date);total+=courses.length;
    // Group intersecting meetings so no card hides another course.
    const groups=[];
    courses.forEach(course=>{
      let group=groups[groups.length-1];
      if(!group||course.section>group.end){group={start:course.section,end:course.sectionEnd,courses:[]};groups.push(group)}
      group.end=Math.max(group.end,course.sectionEnd);group.courses.push(course);
    });
    groups.forEach(group=>{
      html+=`<div class="course-group" style="grid-column:${day+2};grid-row:${group.start} / ${group.end+1};grid-template-rows:repeat(${group.end-group.start+1},minmax(0,1fr))">`;
      group.courses.forEach((course,index)=>{
        const cardIndex=renderedCourseCards.push({course,date})-1,state=weekStateOf(course,date),details=readCourseDetails(course);
        const label=state==='finished'?'已完成':state==='live'?'进行中':'';
        html+=`<button class="course-block color-${courseColor(course)} ${state}" data-course-card="${cardIndex}" style="grid-row:${course.section-group.start+1} / ${course.sectionEnd-group.start+2};grid-column:${index+1}" aria-label="${escAttr(`${course.name}，${fmt(date)}，${periodTime(course)}，${course.place}，${label}`)}"><span class="course-name">${esc(course.name)}</span>${course.place?`<span class="course-place">@${esc(course.place)}</span>`:''}${label?`<span class="course-status">${label}</span>`:''}${details.text.trim()||details.images.length?'<span class="detail-dot" aria-label="已保存课程资料"></span>':''}</button>`;
      });
      html+='</div>';
    });
  }
  notice.innerHTML=total?'':'<div class="week-empty">本周没有课程</div>';
  document.getElementById('list').innerHTML=html;
}

function detailImageMarkup(uri,index){
  return `<figure class="course-image"><img src="${escAttr(uri)}" alt="课程图片 ${index+1}" data-preview-image="${index}" onerror="this.closest('figure').classList.add('unavailable')"><figcaption><span>图片 ${index+1}</span><button data-remove-image="${index}">移除</button></figcaption><div class="image-unavailable">图片已移动、删除或无法访问</div></figure>`;
}
function renderCourseImages(){
  if(!activeCourseDetail)return;
  const details=readCourseDetails(activeCourseDetail.course),container=document.getElementById('courseImages');
  container.innerHTML=details.images.length?details.images.map(detailImageMarkup).join(''):'<div class="empty-images">还没有课程图片。可选择相册、文件管理器中的原图。</div>';
  container.querySelectorAll('[data-preview-image]').forEach(image=>image.onclick=()=>openImagePreview(Number(image.dataset.previewImage)));
  container.querySelectorAll('[data-remove-image]').forEach(button=>button.onclick=()=>{
    const index=Number(button.dataset.removeImage),uri=details.images[index];
    if(!uri)return;
    details.images.splice(index,1);courseDetailsCache.set(courseKey(activeCourseDetail.course),details);
    Android.removeCourseImage(courseKey(activeCourseDetail.course),uri);renderCourseImages();
  });
}
function saveActiveCourseNote(){
  if(!activeCourseDetail)return;
  clearTimeout(noteSaveTimer);noteSaveTimer=null;
  const course=activeCourseDetail.course,key=courseKey(course),details=readCourseDetails(course),text=document.getElementById('courseNote').value;
  details.text=text;courseDetailsCache.set(key,details);Android.saveCourseText(key,text);
  const status=document.getElementById('noteSaveStatus');status.textContent='已保存';setTimeout(()=>{if(status.textContent==='已保存')status.textContent='自动保存'},1400);
}
function openCourseDetail(item){
  if(!item)return;
  if(mapState.active)closeMap();
  activeCourseDetail=item;
  const {course,date}=item,details=readCourseDetails(course);
  document.getElementById('detailTitle').textContent=course.name;
  document.getElementById('detailSummary').innerHTML=`<div><b>${esc(weekdays[date.getDay()])} · ${esc(fmt(date))}</b><span>${esc(periodTime(course))} · ${esc(sectionLabel(course))}</span></div>${course.teacher?`<div><b>教师</b><span>${esc(course.teacher)}</span></div>`:''}${course.place?`<div><b>地点</b><span>${esc(course.place)}</span></div>`:''}<div><b>周次</b><span>${esc(course.weeks||'本学期')}周</span></div>`;
  document.getElementById('courseNote').value=details.text;
  document.getElementById('noteSaveStatus').textContent='自动保存';
  renderCourseImages();
  document.getElementById('courseDetailScreen').classList.remove('hidden');document.body.style.overflow='hidden';
  if(window.Android&&Android.setCourseDetailOpen)Android.setCourseDetailOpen(true);
}
function closeCourseDetail(){
  if(!activeCourseDetail)return;
  saveActiveCourseNote();activeCourseDetail=null;
  document.getElementById('courseDetailScreen').classList.add('hidden');document.body.style.overflow='';
  if(window.Android&&Android.setCourseDetailOpen)Android.setCourseDetailOpen(false);
  render();
}
window.closeCourseDetailFromNative=function(){if(!activeCourseDetail)return false;closeCourseDetail();return true};
window.onCourseImagesChanged=function(key,json){
  let details;try{details=JSON.parse(json)}catch(_){return}
  courseDetailsCache.set(key,{text:String(details.text||''),images:Array.isArray(details.images)?details.images:[]});
  if(activeCourseDetail&&courseKey(activeCourseDetail.course)===key)renderCourseImages();
};

const imagePreview=document.getElementById('imagePreview'),imagePreviewStage=document.getElementById('imagePreviewStage'),imagePreviewContent=document.getElementById('imagePreviewContent');
function renderImagePreview(){
  imagePreviewContent.style.setProperty('--preview-x',`${imagePreviewState.x}px`);
  imagePreviewContent.style.setProperty('--preview-y',`${imagePreviewState.y}px`);
  imagePreviewContent.style.setProperty('--preview-scale',imagePreviewState.scale);
}
function constrainImagePreview(){
  if(imagePreviewState.scale<=1){imagePreviewState.x=0;imagePreviewState.y=0;return}
  const width=imagePreviewContent.clientWidth*imagePreviewState.scale,height=imagePreviewContent.clientHeight*imagePreviewState.scale;
  imagePreviewState.x=Math.max((imagePreviewStage.clientWidth-width)/2,Math.min((width-imagePreviewStage.clientWidth)/2,imagePreviewState.x));
  imagePreviewState.y=Math.max((imagePreviewStage.clientHeight-height)/2,Math.min((height-imagePreviewStage.clientHeight)/2,imagePreviewState.y));
}
function setImagePreviewScale(scale,screenX,screenY){
  const oldScale=imagePreviewState.scale,newScale=Math.max(1,Math.min(6,scale));
  const centerX=imagePreviewStage.clientWidth/2,centerY=imagePreviewStage.clientHeight/2;
  const anchorX=(screenX-centerX-imagePreviewState.x)/oldScale,anchorY=(screenY-centerY-imagePreviewState.y)/oldScale;
  imagePreviewState.scale=newScale;
  imagePreviewState.x=screenX-centerX-anchorX*newScale;
  imagePreviewState.y=screenY-centerY-anchorY*newScale;
  constrainImagePreview();renderImagePreview();
}
function resetImagePreview(){imagePreviewState.scale=1;imagePreviewState.x=0;imagePreviewState.y=0;renderImagePreview()}
function openImagePreview(index){
  if(!activeCourseDetail)return;
  const details=readCourseDetails(activeCourseDetail.course),uri=details.images[index];
  if(!uri)return;
  imagePreviewState.active=true;imagePreviewState.pointers.clear();imagePreviewState.gesture=null;resetImagePreview();
  imagePreviewContent.src=uri;
  document.getElementById('imagePreviewLabel').textContent=`图片 ${index+1} / ${details.images.length}`;
  imagePreview.classList.remove('hidden');imagePreview.setAttribute('aria-hidden','false');
  if(window.Android&&Android.setImagePreviewOpen)Android.setImagePreviewOpen(true);
}
function closeImagePreview(){
  if(!imagePreviewState.active)return;
  imagePreviewState.active=false;imagePreviewState.pointers.clear();imagePreviewState.gesture=null;
  imagePreview.classList.add('hidden');imagePreview.setAttribute('aria-hidden','true');imagePreviewContent.removeAttribute('src');
  if(window.Android&&Android.setImagePreviewOpen)Android.setImagePreviewOpen(false);
}
window.closeImagePreviewFromNative=function(){if(!imagePreviewState.active)return false;closeImagePreview();return true};
function beginImagePreviewGesture(){
  const points=[...imagePreviewState.pointers.values()];
  if(!points.length){imagePreviewState.gesture=null;return}
  if(points.length===1)imagePreviewState.gesture={count:1,point:{...points[0]},x:imagePreviewState.x,y:imagePreviewState.y};
  else{
    const first=points[0],second=points[1],midpoint={x:(first.x+second.x)/2,y:(first.y+second.y)/2};
    imagePreviewState.gesture={count:2,distance:Math.hypot(second.x-first.x,second.y-first.y),midpoint,scale:imagePreviewState.scale,x:imagePreviewState.x,y:imagePreviewState.y};
  }
}
imagePreviewStage.addEventListener('pointerdown',event=>{imagePreviewStage.setPointerCapture(event.pointerId);imagePreviewState.pointers.set(event.pointerId,{x:event.clientX,y:event.clientY});imagePreviewState.moved=false;beginImagePreviewGesture()});
imagePreviewStage.addEventListener('pointermove',event=>{
  if(!imagePreviewState.pointers.has(event.pointerId)||!imagePreviewState.gesture)return;
  imagePreviewState.pointers.set(event.pointerId,{x:event.clientX,y:event.clientY});
  const points=[...imagePreviewState.pointers.values()],gesture=imagePreviewState.gesture;
  if(points.length===1&&gesture.count===1&&imagePreviewState.scale>1){
    imagePreviewState.x=gesture.x+points[0].x-gesture.point.x;imagePreviewState.y=gesture.y+points[0].y-gesture.point.y;
    if(Math.hypot(points[0].x-gesture.point.x,points[0].y-gesture.point.y)>5)imagePreviewState.moved=true;
  }else if(points.length>=2&&gesture.count===2){
    const midpoint={x:(points[0].x+points[1].x)/2,y:(points[0].y+points[1].y)/2};
    const distance=Math.hypot(points[1].x-points[0].x,points[1].y-points[0].y),newScale=Math.max(1,Math.min(6,gesture.scale*distance/Math.max(1,gesture.distance)));
    const centerX=imagePreviewStage.clientWidth/2,centerY=imagePreviewStage.clientHeight/2;
    const anchorX=(gesture.midpoint.x-centerX-gesture.x)/gesture.scale,anchorY=(gesture.midpoint.y-centerY-gesture.y)/gesture.scale;
    imagePreviewState.scale=newScale;imagePreviewState.x=midpoint.x-centerX-anchorX*newScale;imagePreviewState.y=midpoint.y-centerY-anchorY*newScale;imagePreviewState.moved=true;
  }
  constrainImagePreview();renderImagePreview();event.preventDefault();
});
function endImagePreviewPointer(event){imagePreviewState.pointers.delete(event.pointerId);beginImagePreviewGesture()}
imagePreviewStage.addEventListener('pointerup',endImagePreviewPointer);imagePreviewStage.addEventListener('pointercancel',endImagePreviewPointer);
imagePreviewStage.addEventListener('dblclick',event=>setImagePreviewScale(imagePreviewState.scale>1?1:2.5,event.clientX,event.clientY));
document.getElementById('imagePreviewClose').onclick=closeImagePreview;
document.getElementById('imagePreviewZoomIn').onclick=()=>setImagePreviewScale(imagePreviewState.scale*1.5,imagePreviewStage.clientWidth/2,imagePreviewStage.clientHeight/2);
document.getElementById('imagePreviewZoomOut').onclick=()=>setImagePreviewScale(imagePreviewState.scale/1.5,imagePreviewStage.clientWidth/2,imagePreviewStage.clientHeight/2);
document.getElementById('imagePreviewReset').onclick=resetImagePreview;

function solveLinear3(matrix,vector){
  const augmented=matrix.map((row,index)=>[...row,vector[index]]);
  for(let column=0;column<3;column++){
    let pivot=column;
    for(let row=column+1;row<3;row++)if(Math.abs(augmented[row][column])>Math.abs(augmented[pivot][column]))pivot=row;
    [augmented[column],augmented[pivot]]=[augmented[pivot],augmented[column]];
    const divisor=augmented[column][column];
    if(Math.abs(divisor)<1e-10)throw new Error('地图校准点无法形成有效平面');
    for(let item=column;item<4;item++)augmented[column][item]/=divisor;
    for(let row=0;row<3;row++)if(row!==column){
      const factor=augmented[row][column];
      for(let item=column;item<4;item++)augmented[row][item]-=factor*augmented[column][item];
    }
  }
  return augmented.map(row=>row[3]);
}
function fitAxis(rows,values){
  const matrix=Array.from({length:3},()=>Array(3).fill(0)),vector=Array(3).fill(0);
  rows.forEach((row,index)=>{
    for(let i=0;i<3;i++){
      vector[i]+=row[i]*values[index];
      for(let j=0;j<3;j++)matrix[i][j]+=row[i]*row[j];
    }
  });
  return solveLinear3(matrix,vector);
}
function createProjection(controlPoints){
  const longitudeOrigin=controlPoints.reduce((sum,point)=>sum+point.longitude,0)/controlPoints.length;
  const latitudeOrigin=controlPoints.reduce((sum,point)=>sum+point.latitude,0)/controlPoints.length;
  const longitudeScale=111320*Math.cos(latitudeOrigin*Math.PI/180),latitudeScale=110540;
  const rows=controlPoints.map(point=>[(point.longitude-longitudeOrigin)*longitudeScale,(point.latitude-latitudeOrigin)*latitudeScale,1]);
  const xCoefficients=fitAxis(rows,controlPoints.map(point=>point.pixelX));
  const yCoefficients=fitAxis(rows,controlPoints.map(point=>point.pixelY));
  const project=(longitude,latitude)=>{
    const row=[(longitude-longitudeOrigin)*longitudeScale,(latitude-latitudeOrigin)*latitudeScale,1];
    return{x:row.reduce((sum,value,index)=>sum+value*xCoefficients[index],0),y:row.reduce((sum,value,index)=>sum+value*yCoefficients[index],0)};
  };
  const errors=controlPoints.map(point=>{const pixel=project(point.longitude,point.latitude);return Math.hypot(pixel.x-point.pixelX,pixel.y-point.pixelY)});
  const pixelsPerMeter=(Math.hypot(xCoefficients[0],yCoefficients[0])+Math.hypot(xCoefficients[1],yCoefficients[1]))/2;
  const rms=Math.sqrt(errors.reduce((sum,error)=>sum+error*error,0)/errors.length),maximum=Math.max(...errors);
  return{project,rms,maximum,pixelsPerMeter,rmsMeters:rms/pixelsPerMeter,maximumMeters:maximum/pixelsPerMeter};
}

const mapState={active:false,ready:false,manifest:null,buildings:[],projection:null,width:0,height:0,scale:1,minScale:.04,maxScale:1.5,translateX:0,translateY:0,tileLayers:new Map(),activeTileZoom:null,requestedTileZoom:null,tileRequestId:0,target:null,location:null,headingDisplay:null,fitOnNextLocation:false,pointers:new Map(),gesture:null,renderPending:false};
const mapScreen=document.getElementById('mapScreen'),mapViewport=document.getElementById('mapViewport'),mapCanvas=document.getElementById('mapCanvas');
const tileLayer=document.getElementById('tileLayer'),locationMarker=document.getElementById('locationMarker'),targetMarker=document.getElementById('targetMarker');
const accuracyCircle=document.getElementById('accuracyCircle'),locationHeading=document.getElementById('locationHeading'),mapStatus=document.getElementById('mapStatus');

function initializeMap(){
  if(mapState.ready)return true;
  try{
    if(!window.CAMPUS_MAP_MANIFEST||!window.CAMPUS_BUILDINGS)throw new Error('地图数据未打包');
    mapState.manifest=window.CAMPUS_MAP_MANIFEST;
    mapState.buildings=window.CAMPUS_BUILDINGS.features||[];
    mapState.width=mapState.manifest.width;
    mapState.height=mapState.manifest.height;
    mapState.projection=createProjection(mapState.manifest.controlPoints);
    mapCanvas.style.width=`${mapState.width}px`;
    mapCanvas.style.height=`${mapState.height}px`;
    mapState.ready=true;
    mapStatus.textContent=`地图已加载 · ${mapState.buildings.length} 个地点 · 平均校准误差约 ${Math.round(mapState.projection.rmsMeters)} 米`;
    return true;
  }catch(error){
    mapStatus.textContent=`地图加载失败：${error.message}`;
    return false;
  }
}
function projectCoordinates(longitude,latitude){return mapState.projection.project(Number(longitude),Number(latitude))}
function normalizeSearch(value){return String(value||'').toLowerCase().replace(/[\s\-—_·,，。()（）]/g,'')}
function buildingTerms(feature){
  const properties=feature.properties||{};
  return [properties.name,...(properties.aliases||[])].filter(Boolean).sort((a,b)=>b.length-a.length);
}
function matchBuilding(place){
  const normalizedPlace=normalizeSearch(place);
  let best=null;
  mapState.buildings.forEach(feature=>buildingTerms(feature).forEach(term=>{
    const normalizedTerm=normalizeSearch(term);
    if(normalizedTerm.length<2||!normalizedPlace.includes(normalizedTerm))return;
    if(!best||normalizedTerm.length>best.length)best={feature,length:normalizedTerm.length};
  }));
  return best&&best.feature;
}
function chooseDefaultTarget(){
  const course=nextCourseToday();
  if(!course){mapStatus.textContent='今天没有尚未开始的课程，请搜索目标建筑';return}
  const building=matchBuilding(course.place);
  if(!building){mapStatus.textContent=`下一节课地点“${course.place||'待定'}”未匹配，请手动搜索`;return}
  setTarget(building,`下一节课 · ${course.name}`,true);
}
function buildingPixel(feature){
  const name=feature.properties&&feature.properties.name;
  const control=name&&mapState.manifest.controlPoints.find(point=>point.name===name);
  if(control)return{x:Number(control.pixelX),y:Number(control.pixelY)};
  const coordinates=feature.geometry.coordinates;
  return projectCoordinates(coordinates[0],coordinates[1]);
}
function setTarget(feature,reason,automatic){
  const pixel=buildingPixel(feature);
  mapState.target={feature,pixel};
  targetMarker.style.left=`${pixel.x}px`;
  targetMarker.style.top=`${pixel.y}px`;
  targetMarker.classList.remove('hidden');
  document.getElementById('targetLabel').textContent=feature.properties.name;
  mapStatus.textContent=`目标：${feature.properties.name}${reason?` · ${reason}`:''}`;
  mapState.fitOnNextLocation=!mapState.location;
  fitRelevantPoints();
  if(!automatic){document.getElementById('buildingSearch').value='';hideSearchResults()}
}
function fitWholeMap(){
  const width=mapViewport.clientWidth,height=mapViewport.clientHeight;
  mapState.minScale=Math.min(width/mapState.width,height/mapState.height)*.94;
  mapState.scale=mapState.minScale;
  mapState.translateX=(width-mapState.width*mapState.scale)/2;
  mapState.translateY=(height-mapState.height*mapState.scale)/2;
  renderMap();
}
function clampScale(scale){return Math.max(mapState.minScale,Math.min(mapState.maxScale,scale))}
function constrainMap(){
  const width=mapViewport.clientWidth,height=mapViewport.clientHeight,scaledWidth=mapState.width*mapState.scale,scaledHeight=mapState.height*mapState.scale,margin=45;
  if(scaledWidth<=width)mapState.translateX=(width-scaledWidth)/2;
  else mapState.translateX=Math.min(margin,Math.max(width-scaledWidth-margin,mapState.translateX));
  if(scaledHeight<=height)mapState.translateY=(height-scaledHeight)/2;
  else mapState.translateY=Math.min(margin,Math.max(height-scaledHeight-margin,mapState.translateY));
}
function fitPixels(points,singleScale){
  if(!points.length){fitWholeMap();return}
  const width=mapViewport.clientWidth,height=mapViewport.clientHeight;
  const minX=Math.min(...points.map(point=>point.x)),maxX=Math.max(...points.map(point=>point.x));
  const minY=Math.min(...points.map(point=>point.y)),maxY=Math.max(...points.map(point=>point.y));
  const padding=points.length===1?0:360;
  const scale=points.length===1?Math.max(mapState.minScale*4,singleScale||.24):Math.min((width-70)/(maxX-minX+padding),(height-190)/(maxY-minY+padding));
  mapState.scale=clampScale(scale);
  mapState.translateX=width/2-(minX+maxX)/2*mapState.scale;
  mapState.translateY=height/2-(minY+maxY)/2*mapState.scale;
  constrainMap();renderMap();
}
function fitRelevantPoints(){
  const points=[];
  if(mapState.location&&mapState.location.inBounds)points.push(mapState.location.pixel);
  if(mapState.target)points.push(mapState.target.pixel);
  fitPixels(points);
}
function centerLocation(){if(mapState.location&&mapState.location.inBounds)fitPixels([mapState.location.pixel],.3);else mapStatus.textContent='暂未获得校园范围内的位置'}
function setScaleAround(newScale,screenX,screenY){
  const imageX=(screenX-mapState.translateX)/mapState.scale,imageY=(screenY-mapState.translateY)/mapState.scale;
  mapState.scale=clampScale(newScale);
  mapState.translateX=screenX-imageX*mapState.scale;
  mapState.translateY=screenY-imageY*mapState.scale;
  constrainMap();renderMap();
}
function tileZoom(){
  const desired=mapState.manifest.maxZoom+Math.log2(Math.max(.0001,mapState.scale*window.devicePixelRatio));
  const minimum=mapState.manifest.minZoom,maximum=mapState.manifest.maxZoom;
  if(mapState.requestedTileZoom===null)return Math.max(minimum,Math.min(maximum,Math.floor(desired)));
  let zoom=mapState.requestedTileZoom;
  while(zoom<maximum&&desired>=zoom+1.15)zoom++;
  while(zoom>minimum&&desired<zoom-.15)zoom--;
  return zoom;
}
function tileLayerFor(zoom){
  if(mapState.tileLayers.has(zoom))return mapState.tileLayers.get(zoom);
  const element=document.createElement('div');element.className='tile-zoom-layer';element.dataset.zoom=zoom;tileLayer.appendChild(element);
  const layer={element,tiles:new Map()};mapState.tileLayers.set(zoom,layer);return layer;
}
function updateTiles(){
  if(!mapState.active||!mapState.ready)return;
  const zoom=tileZoom(),levelScale=Math.pow(2,zoom-mapState.manifest.maxZoom),tileSize=mapState.manifest.tileSize;
  mapState.requestedTileZoom=zoom;
  const buffer=Math.min(320,192/mapState.scale);
  const left=Math.max(0,(-mapState.translateX/mapState.scale)-buffer),top=Math.max(0,(-mapState.translateY/mapState.scale)-buffer);
  const right=Math.min(mapState.width,((mapViewport.clientWidth-mapState.translateX)/mapState.scale)+buffer),bottom=Math.min(mapState.height,((mapViewport.clientHeight-mapState.translateY)/mapState.scale)+buffer);
  const levelWidth=Math.ceil(mapState.width*levelScale),levelHeight=Math.ceil(mapState.height*levelScale);
  const maxColumn=Math.ceil(levelWidth/tileSize)-1,maxRow=Math.ceil(levelHeight/tileSize)-1;
  const firstColumn=Math.max(0,Math.floor(left*levelScale/tileSize)),lastColumn=Math.min(maxColumn,Math.floor(right*levelScale/tileSize));
  const firstRow=Math.max(0,Math.floor(top*levelScale/tileSize)),lastRow=Math.min(maxRow,Math.floor(bottom*levelScale/tileSize));
  const needed=new Set(),layer=tileLayerFor(zoom),requestId=++mapState.tileRequestId;
  const finish=()=>{
    if(requestId!==mapState.tileRequestId||zoom!==mapState.requestedTileZoom)return;
    if([...needed].some(key=>layer.tiles.get(key)?.dataset.loaded!=='true'))return;
    layer.tiles.forEach((image,key)=>{if(!needed.has(key)){image.remove();layer.tiles.delete(key)}});
    if(mapState.activeTileZoom===zoom){layer.element.classList.add('active');return}
    const previous=mapState.activeTileZoom===null?null:mapState.tileLayers.get(mapState.activeTileZoom);
    layer.element.style.zIndex='2';layer.element.classList.add('active');mapState.activeTileZoom=zoom;
    if(previous){previous.element.style.zIndex='1';setTimeout(()=>{if(mapState.activeTileZoom===zoom)previous.element.classList.remove('active')},140)}
  };
  for(let column=firstColumn;column<=lastColumn;column++)for(let row=firstRow;row<=lastRow;row++){
    const key=`${zoom}/${column}/${row}`;needed.add(key);
    if(layer.tiles.has(key))continue;
    const image=document.createElement('img');
    const actualWidth=Math.min(tileSize,levelWidth-column*tileSize),actualHeight=Math.min(tileSize,levelHeight-row*tileSize);
    image.alt='';image.draggable=false;
    image.style.left=`${column*tileSize/levelScale}px`;image.style.top=`${row*tileSize/levelScale}px`;
    image.style.width=`${actualWidth/levelScale}px`;image.style.height=`${actualHeight/levelScale}px`;
    image.onload=()=>{image.dataset.loaded='true';if(mapState.active&&zoom===mapState.requestedTileZoom)updateTiles()};
    image.onerror=()=>{image.dataset.loaded='true';if(mapState.active&&zoom===mapState.requestedTileZoom)updateTiles()};
    image.src=`map/tiles/${key}.${mapState.manifest.extension}`;
    layer.element.appendChild(image);layer.tiles.set(key,image);
    if(image.complete)image.dataset.loaded='true';
  }
  finish();
}
function renderMap(){
  if(!mapState.ready||mapState.renderPending)return;
  mapState.renderPending=true;
  requestAnimationFrame(()=>{
    mapState.renderPending=false;
    mapCanvas.style.transform=`translate(${mapState.translateX}px,${mapState.translateY}px) scale(${mapState.scale})`;
    const inverse=1/mapState.scale;
    document.querySelectorAll('.marker-scale').forEach(marker=>marker.style.setProperty('--marker-inverse',inverse));
    if(!(mapState.gesture&&mapState.gesture.count===2))updateTiles();
  });
}
function openMap(){
  document.body.classList.add('map-open');
  document.getElementById('mapFab').setAttribute('aria-label','返回课程表');
  mapState.active=true;mapScreen.classList.remove('hidden');document.body.style.overflow='hidden';
  if(window.Android&&Android.setMapOpen)Android.setMapOpen(true);
  document.getElementById('mapFabText').textContent='课表';document.getElementById('mapFabIcon').textContent='▤';
  if(!initializeMap())return;
  requestAnimationFrame(()=>{fitWholeMap();if(!mapState.target)chooseDefaultTarget();if(window.Android&&Android.requestLocation)Android.requestLocation()});
}
function closeMap(){
  document.body.classList.remove('map-open');
  document.getElementById('mapFab').setAttribute('aria-label','打开校园地图');
  mapState.active=false;mapScreen.classList.add('hidden');document.body.style.overflow='';hideSearchResults();
  if(window.Android&&Android.setMapOpen)Android.setMapOpen(false);
  document.getElementById('mapFabText').textContent='地图';document.getElementById('mapFabIcon').textContent='⌖';
  if(window.Android&&Android.stopLocation)Android.stopLocation();
}
function toggleMap(){mapState.active?closeMap():openMap()}
window.closeMapFromNative=function(){if(!mapState.active)return false;closeMap();return true};
window.onNativeLocation=function(latitude,longitude,accuracy,time){
  if(!mapState.ready)return;
  const pixel=projectCoordinates(longitude,latitude),inBounds=pixel.x>=0&&pixel.x<=mapState.width&&pixel.y>=0&&pixel.y<=mapState.height;
  mapState.location={latitude,longitude,accuracy,time,pixel,inBounds};
  if(!inBounds){locationMarker.classList.add('hidden');accuracyCircle.classList.add('hidden');mapStatus.textContent='当前位置不在校园地图范围内';return}
  locationMarker.style.left=`${pixel.x}px`;locationMarker.style.top=`${pixel.y}px`;locationMarker.classList.remove('hidden');
  const northPixel=projectCoordinates(longitude,latitude+accuracy/110540),radius=Math.max(12,Math.hypot(northPixel.x-pixel.x,northPixel.y-pixel.y));
  accuracyCircle.style.left=`${pixel.x}px`;accuracyCircle.style.top=`${pixel.y}px`;accuracyCircle.style.width=`${radius*2}px`;accuracyCircle.style.height=`${radius*2}px`;accuracyCircle.classList.remove('hidden');
  mapStatus.textContent=`我的位置 · 精度约 ±${Math.round(accuracy)} 米${mapState.target?` · 目标 ${mapState.target.feature.properties.name}`:''}`;
  if(mapState.fitOnNextLocation){mapState.fitOnNextLocation=false;fitRelevantPoints()}else renderMap();
};
window.onNativeHeading=function(heading){
  heading=Number(heading);if(!Number.isFinite(heading))return;
  if(mapState.headingDisplay===null)mapState.headingDisplay=heading;
  else{
    const normalized=((mapState.headingDisplay%360)+360)%360;
    mapState.headingDisplay+=((heading-normalized+540)%360)-180;
  }
  locationHeading.style.setProperty('--location-heading',`${mapState.headingDisplay}deg`);locationHeading.classList.add('has-heading');
};
window.onLocationPermissionDenied=function(){mapStatus.textContent='未获得定位权限，仍可搜索并查看目标建筑'};
window.onLocationUnavailable=function(){mapStatus.textContent='定位服务不可用，请检查系统定位开关'};

function renderSearchResults(){
  const input=document.getElementById('buildingSearch'),query=normalizeSearch(input.value),container=document.getElementById('searchResults');
  document.getElementById('clearSearch').classList.toggle('hidden',!query);
  if(!query){hideSearchResults();return}
  const matches=mapState.buildings.map(feature=>{
    const terms=buildingTerms(feature),normalized=terms.map(normalizeSearch);
    let score=0;
    normalized.forEach(term=>{if(term===query)score=Math.max(score,1000+term.length);else if(term.startsWith(query))score=Math.max(score,500+term.length);else if(term.includes(query)||query.includes(term))score=Math.max(score,100+term.length)});
    return{feature,score};
  }).filter(result=>result.score>0).sort((a,b)=>b.score-a.score||a.feature.properties.name.localeCompare(b.feature.properties.name,'zh-CN')).slice(0,10);
  container.innerHTML=matches.length?matches.map((result,index)=>`<button class="search-result" data-result="${index}"><b>${esc(result.feature.properties.name)}</b><span>${esc(result.feature.properties.category||'校园地点')}</span></button>`).join(''):'<div class="search-empty">没有找到匹配的校园建筑</div>';
  container.classList.remove('hidden');
  container.querySelectorAll('[data-result]').forEach(button=>button.onclick=()=>setTarget(matches[Number(button.dataset.result)].feature,'手动选择',false));
}
function hideSearchResults(){document.getElementById('searchResults').classList.add('hidden')}

function beginGesture(){
  const points=[...mapState.pointers.values()];
  if(!points.length){mapState.gesture=null;return}
  if(points.length===1)mapState.gesture={count:1,point:{...points[0]},translateX:mapState.translateX,translateY:mapState.translateY};
  else{
    const first=points[0],second=points[1],midpoint={x:(first.x+second.x)/2,y:(first.y+second.y)/2};
    mapState.gesture={count:2,distance:Math.hypot(second.x-first.x,second.y-first.y),midpoint,scale:mapState.scale,translateX:mapState.translateX,translateY:mapState.translateY,anchorX:(midpoint.x-mapState.translateX)/mapState.scale,anchorY:(midpoint.y-mapState.translateY)/mapState.scale};
  }
}
mapViewport.addEventListener('pointerdown',event=>{mapViewport.setPointerCapture(event.pointerId);mapState.pointers.set(event.pointerId,{x:event.clientX,y:event.clientY});mapViewport.classList.add('dragging');beginGesture()});
mapViewport.addEventListener('pointermove',event=>{
  if(!mapState.pointers.has(event.pointerId)||!mapState.gesture)return;
  mapState.pointers.set(event.pointerId,{x:event.clientX,y:event.clientY});
  const points=[...mapState.pointers.values()],gesture=mapState.gesture;
  if(points.length===1&&gesture.count===1){mapState.translateX=gesture.translateX+points[0].x-gesture.point.x;mapState.translateY=gesture.translateY+points[0].y-gesture.point.y}
  else if(points.length>=2&&gesture.count===2){
    const midpoint={x:(points[0].x+points[1].x)/2,y:(points[0].y+points[1].y)/2};
    const distance=Math.hypot(points[1].x-points[0].x,points[1].y-points[0].y);
    mapState.scale=clampScale(gesture.scale*distance/Math.max(1,gesture.distance));
    mapState.translateX=midpoint.x-gesture.anchorX*mapState.scale;mapState.translateY=midpoint.y-gesture.anchorY*mapState.scale;
  }
  constrainMap();renderMap();event.preventDefault();
});
function endPointer(event){mapState.pointers.delete(event.pointerId);if(!mapState.pointers.size)mapViewport.classList.remove('dragging');beginGesture();constrainMap();renderMap()}
mapViewport.addEventListener('pointerup',endPointer);mapViewport.addEventListener('pointercancel',endPointer);
mapViewport.addEventListener('wheel',event=>{event.preventDefault();const bounds=mapViewport.getBoundingClientRect();setScaleAround(mapState.scale*(event.deltaY<0?1.25:.8),event.clientX-bounds.left,event.clientY-bounds.top)},{passive:false});
mapViewport.addEventListener('dblclick',event=>{const bounds=mapViewport.getBoundingClientRect();setScaleAround(mapState.scale*1.7,event.clientX-bounds.left,event.clientY-bounds.top)});

function toggle(id,on){document.getElementById(id).classList.toggle('hidden',!on)}
function startSync(){if(Android.hasCredentials())Android.beginSync();else toggle('loginMask',true)}
function submitLogin(){const username=document.getElementById('username').value.trim(),password=document.getElementById('password').value;if(!username||!password){document.getElementById('username').focus();return}toggle('loginMask',false);toggle('loadingMask',true);Android.login(username,password)}
function submitCaptcha(){const code=document.getElementById('captchaCode').value.trim();if(!code){document.getElementById('captchaCode').focus();return}toggle('captchaMask',false);toggle('loadingMask',true);Android.submitCaptcha(code)}
function refreshCaptchaImage(){document.getElementById('captchaImage').removeAttribute('src');Android.refreshCaptcha()}
function moveFocusedInput(){setTimeout(()=>document.activeElement&&document.activeElement.scrollIntoView({block:'center',behavior:'smooth'}),180)}
function notice(){
  const parameters=new URLSearchParams(location.search),state=parameters.get('sync');
  if(state==='loading'){toggle('loadingMask',true);return}
  if(state==='login-required'){toggle('loginMask',true);return}
  if(state==='captcha'){toggle('captchaMask',true);document.getElementById('captchaImage').src=Android.getCaptchaImage();return}
  if(!state)return;
  const element=document.createElement('div');element.className=`toast ${state==='failed'?'error':''}`;
  element.textContent=state==='success'?'课表已同步成功，已按当前周次更新。':(parameters.get('message')||'同步失败，请稍后重试。');
  document.body.appendChild(element);setTimeout(()=>element.remove(),4500);
}
function refreshAtNextMinute(){
  function tick(){render();const now=new Date();setTimeout(tick,60000-now.getSeconds()*1000-now.getMilliseconds())}
  const now=new Date();setTimeout(tick,60000-now.getSeconds()*1000-now.getMilliseconds());
}

document.getElementById('currentWeek').onclick=()=>{displayedMonday=monday(new Date());render()};
document.getElementById('weekSelect').onchange=event=>changeWeek(Number(event.target.value)-weekNo(displayedMonday));
document.getElementById('list').addEventListener('click',event=>{const card=event.target.closest('[data-course-card]');if(card&&Date.now()>suppressCourseClickUntil)openCourseDetail(renderedCourseCards[Number(card.dataset.courseCard)])});

document.getElementById('detailBack').onclick=closeCourseDetail;
document.getElementById('courseNote').addEventListener('input',()=>{document.getElementById('noteSaveStatus').textContent='正在保存…';clearTimeout(noteSaveTimer);noteSaveTimer=setTimeout(saveActiveCourseNote,500)});
document.getElementById('courseNote').addEventListener('blur',saveActiveCourseNote);
document.getElementById('addCourseImage').onclick=()=>{if(activeCourseDetail)Android.chooseCourseImages(courseKey(activeCourseDetail.course))};
document.querySelectorAll('.login-panel input').forEach(input=>input.addEventListener('focus',moveFocusedInput));
document.getElementById('mapFab').onclick=toggleMap;
document.getElementById('buildingSearch').addEventListener('input',renderSearchResults);
document.getElementById('buildingSearch').addEventListener('focus',renderSearchResults);
document.getElementById('clearSearch').onclick=()=>{document.getElementById('buildingSearch').value='';renderSearchResults();document.getElementById('buildingSearch').focus()};
document.getElementById('locateButton').onclick=centerLocation;
document.getElementById('fitButton').onclick=fitRelevantPoints;
document.getElementById('zoomInButton').onclick=()=>setScaleAround(mapState.scale*1.4,mapViewport.clientWidth/2,mapViewport.clientHeight/2);
document.getElementById('zoomOutButton').onclick=()=>setScaleAround(mapState.scale/1.4,mapViewport.clientWidth/2,mapViewport.clientHeight/2);
window.addEventListener('resize',()=>{if(mapState.active){constrainMap();renderMap()}});
let swipeStart=null;
const scheduleSurface=document.querySelector('.timetable-main');
scheduleSurface.addEventListener('touchstart',event=>{
  swipeStart=event.touches.length===1?{x:event.touches[0].clientX,y:event.touches[0].clientY}:null;
},{passive:true});
scheduleSurface.addEventListener('touchmove',event=>{
  if(!swipeStart)return;
  if(event.touches.length!==1){swipeStart=null;return}
  const dx=event.touches[0].clientX-swipeStart.x,dy=event.touches[0].clientY-swipeStart.y;
  if(Math.abs(dx)>10||Math.abs(dy)>10)suppressCourseClickUntil=Date.now()+500;
  if(Math.abs(dy)>Math.abs(dx)&&Math.abs(dy)>15)swipeStart=null;
},{passive:true});
scheduleSurface.addEventListener('touchcancel',()=>{swipeStart=null});
scheduleSurface.addEventListener('touchend',event=>{
  if(!swipeStart)return;
  const end=event.changedTouches[0],dx=end.clientX-swipeStart.x,dy=end.clientY-swipeStart.y;swipeStart=null;
  if(Math.abs(dx)<60||Math.abs(dx)<=Math.abs(dy)*1.5)return;
  suppressCourseClickUntil=Date.now()+500;changeWeek(dx<0?1:-1);
},{passive:true});

try{const raw=Android.getSchedule();schedule=raw?JSON.parse(raw):null}catch(_){schedule=null}
notice();render();refreshAtNextMinute();
