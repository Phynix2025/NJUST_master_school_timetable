package cn.edu.njust.kezaizhangxin

import android.app.*
import android.content.*
import android.os.Build
import org.json.JSONObject
import java.time.*

class ScheduleNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_NIGHT) {
            notifyTomorrow(context)
            NotificationScheduler.schedule(context)
        } else if (intent.action == ACTION_COURSE) {
            show(context, "上课提醒", intent.getStringExtra("message") ?: "课程将在 20 分钟后开始", intent.getIntExtra("id", 0))
        } else NotificationScheduler.schedule(context)
    }

    private fun notifyTomorrow(context: Context) {
        if (LocalDate.now().dayOfWeek in setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) return
        val tomorrow = LocalDate.now().plusDays(1)
        val courses = NotificationScheduler.coursesOn(context, tomorrow)
        val text = courses.minByOrNull { it.section }?.let { "明天最早一节课在 ${NotificationScheduler.timeOf(it.section)}" }
            ?: "明天没课，好好放松吧"
        show(context, "明日课程提醒", text, 1)
    }

    private fun show(context: Context, title: String, text: String, id: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "课程提醒", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open).build()
        manager.notify(id, notification)
    }
    companion object { const val CHANNEL="course_reminders"; const val ACTION_NIGHT="cn.edu.njust.kezaizhangxin.NIGHT"; const val ACTION_COURSE="cn.edu.njust.kezaizhangxin.COURSE" }
}

data class Course(val section:Int, val text:String)

object NotificationScheduler {
    private val times = mapOf(1 to "08:00",2 to "08:50",3 to "09:40",4 to "10:40",5 to "11:30",6 to "14:00",7 to "14:50",8 to "15:50",9 to "16:40",10 to "17:30",11 to "19:00",12 to "19:50",13 to "20:40")
    fun timeOf(section:Int) = times[section] ?: "08:00"
    fun schedule(context: Context) {
        val alarm=context.getSystemService(AlarmManager::class.java); val now=ZonedDateTime.now()
        var night=now.withHour(22).withMinute(0).withSecond(0).withNano(0); if(!night.isAfter(now)) night=night.plusDays(1)
        set(alarm, context, night.toInstant().toEpochMilli(), ScheduleNotificationReceiver.ACTION_NIGHT, 10, null)
        for (offset in 0..14) { val date=LocalDate.now().plusDays(offset.toLong()); coursesOn(context,date).forEachIndexed { index, course ->
            val t=LocalTime.parse(timeOf(course.section)).minusMinutes(20); val whenAt=date.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (whenAt > System.currentTimeMillis()) set(alarm,context,whenAt,ScheduleNotificationReceiver.ACTION_COURSE,(whenAt xor index.toLong()).toInt(),"${timeOf(course.section)} 上课 · ${course.text}")
        }}
    }
    private fun set(alarm:AlarmManager,context:Context,at:Long,action:String,id:Int,message:String?) { val i=Intent(context,ScheduleNotificationReceiver::class.java).setAction(action).putExtra("message",message).putExtra("id",id); val p=PendingIntent.getBroadcast(context,id,i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); if(Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) alarm.set(AlarmManager.RTC_WAKEUP,at,p) else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,p) }
    fun coursesOn(context: Context, date:LocalDate):List<Course> { return try { val raw=context.getSharedPreferences("schedule",Context.MODE_PRIVATE).getString("data","") ?: return emptyList(); val root=JSONObject(raw); val week=week(root,date); if(week<1)return emptyList(); val day=date.dayOfWeek.value; val result=mutableListOf<Course>(); val last=hashSetOf<String>(); for(i in 0 until root.getJSONArray("slots").length()){val s=root.getJSONArray("slots").getJSONObject(i); val weekday=s.getString("weekday").let { listOf("星期一","星期二","星期三","星期四","星期五","星期六","星期日").indexOf(it)+1 }; if(weekday!=day)continue; val text=s.getString("text"); if(!inWeeks(text,week))continue; val key="$text|${s.getInt("section")}"; if(last.add(key)) result+=Course(s.getInt("section"),text)}; result } catch(_:Exception){emptyList()} }
    private fun week(root:JSONObject,date:LocalDate):Int { val anchor=root.optString("termWeek1"); if(anchor.isNotBlank()) return (java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(anchor),date)/7).toInt()+1; return root.optInt("syncWeek",0) }
    private fun inWeeks(text:String,week:Int):Boolean { val spec=Regex("([0-9、,，\\s\\-–]+)周").find(text)?.groupValues?.get(1) ?: return true; return Regex("\\d+(?:\\s*[-–]\\s*\\d+)?").findAll(spec).any { val n=it.value.split(Regex("[-–]")).map{v->v.trim().toInt()}; week>=n.first()&&week<=n.last() } }
}
