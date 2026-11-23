package com.example.miniquiz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val Ink = Color(0xFF17212B)
private val Muted = Color(0xFF78848F)
private val PageCanvas = Color(0xFFF5F7F8)
private val Purple = Color(0xFF6E63E8)
private val Green = Color(0xFF27A786)
private val Red = Color(0xFFE56D72)
private val Line = Color(0xFFE7EBEF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { QuizApp(this) }
    }
}

private data class Quiz(val id: String, val title: String, val subtitle: String, val icon: String, val color: Color, val questions: List<Question>)
private data class Question(val id: Int, val text: String, val options: List<String>, val correct: Int, val timeSec: Int, val points: Int)
private data class Answer(val selected: Int?, val correct: Boolean, val points: Int, val timedOut: Boolean)
private enum class Page { Login, Home, Rules, Play, Results, Review, Account }

@Composable
private fun QuizApp(activity: ComponentActivity) {
    val prefs = remember { activity.getSharedPreferences("quizApp_v1", 0) }
    var page by remember { mutableStateOf(if (prefs.getString("user", "").orEmpty().isNotBlank()) Page.Home else Page.Login) }
    var quizzes by remember { mutableStateOf<List<Quiz>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selectedQuiz by remember { mutableStateOf<Quiz?>(null) }
    var questionIndex by remember { mutableIntStateOf(0) }
    var selectedOption by remember { mutableIntStateOf(-1) }
    var answer by remember { mutableStateOf<Answer?>(null) }
    var answers by remember { mutableStateOf(listOf<Answer>()) }
    var secondsLeft by remember { mutableIntStateOf(20) }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf(prefs.getString("user", "") ?: "") }
    var loginError by remember { mutableStateOf<String?>(null) }
    var quizStartedAt by remember { mutableLongStateOf(0L) }

    fun loadQuizzes() {
        loadError = null
        try {
            val raw = activity.assets.open("quizzes.json").bufferedReader().use { it.readText() }
            val root = JSONArray(raw)
            val loaded = (0 until root.length()).map { i ->
                val qz = root.getJSONObject(i)
                val qArray = qz.getJSONArray("questions")
                val parsed = (0 until qArray.length()).map { j ->
                    val item = qArray.getJSONObject(j)
                    val optionsJson = item.getJSONArray("options")
                    val options = (0 until optionsJson.length()).map { optionsJson.getString(it) }
                    val correctIndex = item.getInt("correctIndex")
                    require(item.optString("question").isNotBlank() && options.size >= 2 && correctIndex in options.indices) { "Некорректная структура вопроса" }
                    Question(item.optInt("id", j + 1), item.getString("question"), options, correctIndex, item.optInt("timeLimitSec", 20).coerceAtLeast(5), item.optInt("points", 10).coerceAtLeast(0))
                }
                require(parsed.isNotEmpty()) { "В теме нет вопросов" }
                Quiz(qz.getString("id"), qz.getString("title"), qz.optString("subtitle"), qz.optString("icon", "✦"), Color(android.graphics.Color.parseColor(qz.optString("color", "#6E63E8"))), parsed)
            }
            quizzes = loaded
        } catch (e: Exception) {
            android.util.Log.e("MiniQuiz", "Не удалось загрузить или проверить quizzes.json", e)
            loadError = "Не удалось загрузить вопросы. Проверьте данные и попробуйте ещё раз."
            quizzes = emptyList()
        }
    }

    fun startQuiz(quiz: Quiz) {
        selectedQuiz = quiz
        questionIndex = 0
        selectedOption = -1
        answer = null
        answers = emptyList()
        secondsLeft = quiz.questions.first().timeSec
        quizStartedAt = System.currentTimeMillis()
        page = Page.Play
    }

    fun finishQuestion(result: Answer) {
        if (answer != null) return
        answer = result
        selectedOption = result.selected ?: -1
        answers = answers + result
    }

    fun nextQuestion() {
        val quiz = selectedQuiz ?: return
        if (questionIndex + 1 >= quiz.questions.size) {
            val score = answers.sumOf { it.points }
            val key = "best_${quiz.id}"
            val previous = prefs.getInt(key, 0)
            if (score > previous) prefs.edit().putInt(key, score).apply()
            val history = try { JSONArray(prefs.getString("history", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
            history.put(JSONObject().put("quiz", quiz.title).put("score", score).put("durationSec", ((System.currentTimeMillis() - quizStartedAt) / 1000).toInt()).put("date", System.currentTimeMillis()))
            val compact = JSONArray()
            for (i in (history.length() - 10).coerceAtLeast(0) until history.length()) compact.put(history.getJSONObject(i))
            prefs.edit().putString("history", compact.toString()).apply()
            page = Page.Results
        } else {
            questionIndex++
            selectedOption = -1
            answer = null
            secondsLeft = quiz.questions[questionIndex].timeSec
        }
    }

    LaunchedEffect(Unit) { loadQuizzes() }
    val currentQuiz = selectedQuiz
    val currentQuestion = currentQuiz?.questions?.getOrNull(questionIndex)
    LaunchedEffect(page, questionIndex, currentQuestion, answer == null) {
        if (page == Page.Play && answer == null && currentQuestion != null) {
            secondsLeft = currentQuestion.timeSec
            while (secondsLeft > 0 && answer == null) {
                delay(1_000)
                if (answer == null) secondsLeft--
            }
            if (secondsLeft <= 0 && answer == null) finishQuestion(Answer(null, false, 0, true))
        }
    }
    LaunchedEffect(page, questionIndex, answer?.timedOut) {
        if (page == Page.Play && answer?.timedOut == true) { delay(1_200); nextQuestion() }
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Purple, background = PageCanvas, surface = Color.White, onSurface = Ink)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF53B2C5), Color(0xFF5566BD), Color(0xFF562FA9))))) {
            BackdropDecor()
            AnimatedContent(targetState = page, label = "quiz-pages") { target ->
                when (target) {
                    Page.Login -> LoginScreen(login, { login = it }, password, { password = it }, loginError) {
                        if (login.isBlank() || password.isBlank()) loginError = "Введите логин и пароль"
                        else { userName = login.trim(); prefs.edit().putString("user", userName).apply(); loginError = null; page = Page.Home }
                    }
                    Page.Home -> HomeScreen(
                        userName, quizzes, loadError, prefs, onRetry = ::loadQuizzes,
                        onSelect = { selectedQuiz = it; page = Page.Rules }, onProfile = { page = Page.Account },
                        onLogout = { userName = ""; prefs.edit().remove("user").apply(); page = Page.Login }
                    )
                    Page.Rules -> currentQuiz?.let { RulesScreen(it, onBack = { page = Page.Home }, onStart = { startQuiz(it) }) }
                    Page.Play -> if (currentQuiz != null && currentQuestion != null) QuestionScreen(
                        currentQuiz, currentQuestion, questionIndex, secondsLeft, selectedOption, answer,
                        onBack = { page = Page.Rules }, onSelect = { if (answer == null) selectedOption = it },
                        onSubmit = { if (selectedOption >= 0) finishQuestion(Answer(selectedOption, selectedOption == currentQuestion.correct, if (selectedOption == currentQuestion.correct) currentQuestion.points else 0, false)) },
                        onNext = ::nextQuestion
                    )
                    Page.Results -> if (currentQuiz != null) ResultsScreen(currentQuiz, answers, prefs.getInt("best_${currentQuiz.id}", 0), onReview = { page = Page.Review }, onAgain = { startQuiz(currentQuiz) }, onHome = { page = Page.Home })
                    Page.Review -> if (currentQuiz != null) ReviewScreen(currentQuiz, answers, onBack = { page = Page.Results })
                    Page.Account -> AccountScreen(userName, prefs, onBack = { page = Page.Home }, onLogout = { userName = ""; prefs.edit().remove("user").apply(); page = Page.Login })
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(login: String, onLogin: (String) -> Unit, password: String, onPassword: (String) -> Unit, error: String?, onEnter: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(72.dp))
        Text("Mini\nQuiz", fontSize = 43.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        Spacer(Modifier.height(10.dp))
        Text("Перед тем как начать игру,\nвойдите в аккаунт", color = Color.White.copy(alpha = .92f), fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(34.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(20.dp)) {
            Text("Войти в аккаунт", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(value = login, onValueChange = onLogin, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Логин") }, leadingIcon = { Icon(Icons.Default.Person, null) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None), shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = password, onValueChange = onPassword, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Пароль") }, leadingIcon = { Icon(Icons.Default.Lock, null) }, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp))
            if (error != null) { Spacer(Modifier.height(8.dp)); Text(error, color = Red, fontSize = 13.sp) }
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Продолжить", onEnter)
        }
        Spacer(Modifier.height(20.dp))
        Text("Аккаунт работает локально на этом устройстве", color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun HomeScreen(user: String, quizzes: List<Quiz>, error: String?, prefs: android.content.SharedPreferences, onRetry: () -> Unit, onSelect: (Quiz) -> Unit, onProfile: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Доступные викторины:", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
            TextButton(onClick = onLogout) { Text("Выйти", color = Color.White, fontSize = 13.sp) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(6.dp))
            Spacer(Modifier.height(16.dp))
            when {
                error != null -> EmptyCard("Не удалось загрузить темы", error, "Повторить", onRetry)
                quizzes.isEmpty() -> Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
                else -> quizzes.forEach { quiz ->
                    QuizCard(quiz, prefs.getInt("best_${quiz.id}", 0), onSelect)
                    Spacer(Modifier.height(10.dp))
                }
            }
            Text("Новые викторины скоро появятся!", color = Color.White, fontSize = 17.sp, lineHeight = 23.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 15.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 38.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { }) { Text("▣  Квизы", color = Color.White, fontSize = 14.sp) }
            TextButton(onClick = onProfile) { Text("♙  Личный кабинет", color = Color.White, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun RulesScreen(quiz: Quiz, onBack: () -> Unit, onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        BackHeader("Правила", onBack, light = true)
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(19.dp)) {
            Text(quiz.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(10.dp))
            InfoRow("Всего вопросов", "${quiz.questions.size}")
            InfoRow("За правильный ответ", "${quiz.questions.first().points} баллов")
            InfoRow("На один вопрос", "${quiz.questions.first().timeSec} секунд")
            Text("Ответьте на вопросы за ограниченное время и наберите как можно больше баллов.", color = Ink, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onBack, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(8.dp)) { Text("Назад", color = Ink) }
            Button(onStart, Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Purple)) { Text("Начать") }
        }
    }
}

@Composable
private fun QuestionScreen(quiz: Quiz, question: Question, index: Int, timeLeft: Int, selected: Int, answer: Answer?, onBack: () -> Unit, onSelect: (Int) -> Unit, onSubmit: () -> Unit, onNext: () -> Unit) {
    val progress = (index + 1f) / quiz.questions.size
    val timerProgress by animateFloatAsState(timeLeft.toFloat() / question.timeSec, label = "timer")
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
        BackHeader(quiz.title, onBack, trailing = "${index + 1}/${quiz.questions.size}", light = true)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("ВОПРОС ${index + 1} ИЗ ${quiz.questions.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 1.sp)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("◷", color = if (timeLeft <= 5) Red else Purple, fontSize = 18.sp); Spacer(Modifier.width(5.dp)); Text("00:${timeLeft.toString().padStart(2, '0')}", color = if (timeLeft <= 5) Red else Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = Purple, trackColor = Line)
        Spacer(Modifier.height(27.dp))
        LinearProgressIndicator(progress = { timerProgress }, modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape), color = if (timeLeft <= 5) Red else Color(0xFFB8B2FA), trackColor = Color.Transparent)
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White).padding(15.dp)) {
            Text(question.text, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text("Выберите один вариант ответа", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 7.dp))
        }
        Spacer(Modifier.height(13.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            question.options.forEachIndexed { i, option ->
                val isCorrect = answer != null && i == question.correct
                val isWrongChoice = answer != null && answer.selected == i && !answer.correct
                val background by animateColorAsState(when { isCorrect -> Color(0xFFE6F7F1); isWrongChoice -> Color(0xFFFFECEC); selected == i && answer == null -> Color(0xFFF0EEFF); else -> Color.White }, label = "option-bg")
                val borderColor = when { isCorrect -> Green; isWrongChoice -> Red; selected == i && answer == null -> Purple; else -> Line }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(background).border(1.dp, borderColor, RoundedCornerShape(16.dp)).clickable(enabled = answer == null) { onSelect(i) }.padding(horizontal = 15.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(31.dp).clip(CircleShape).background(if (isCorrect) Green else if (isWrongChoice) Red else if (selected == i) Purple else PageCanvas), contentAlignment = Alignment.Center) {
                        if (isCorrect) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(17.dp)) else if (isWrongChoice) Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(17.dp)) else Text(('A' + i).toString(), color = if (selected == i) Color.White else Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(12.dp)); Text(option, Modifier.weight(1f), fontSize = 15.sp, color = Ink, lineHeight = 20.sp)
                    if (answer != null && i == question.correct) Text("+${question.points}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Green)
                }
            }
        }
        if (answer != null) {
            Spacer(Modifier.height(12.dp))
            Text(when { answer.timedOut -> "Время вышло — вопрос пропущен"; answer.correct -> "Отлично! +${answer.points} баллов"; else -> "Не совсем. Правильный ответ отмечен" }, color = if (answer.correct) Green else if (answer.timedOut) Muted else Red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        if (answer == null) PrimaryButton("Ответить", onSubmit, enabled = selected >= 0)
        else PrimaryButton(if (index == quiz.questions.size - 1) "К результатам" else "Далее", onNext, trailing = true)
    }
}

@Composable
private fun ResultsScreen(quiz: Quiz, answers: List<Answer>, best: Int, onReview: () -> Unit, onAgain: () -> Unit, onHome: () -> Unit) {
    val correct = answers.count { it.correct }
    val missed = answers.count { it.timedOut }
    val wrong = answers.size - correct - missed
    val score = answers.sumOf { it.points }
    val max = quiz.questions.sumOf { it.points }
    val percent = if (quiz.questions.isEmpty()) 0 else correct * 100 / quiz.questions.size
    val isRecord = score == best && score > 0
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BackHeader("Итог", onHome, light = true)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(110.dp).clip(CircleShape).background(Color(0xFFFFF3D9)), contentAlignment = Alignment.Center) { Icon(Icons.Default.EmojiEvents, null, tint = Color(0xFFE5A631), modifier = Modifier.size(55.dp)) }
        Text(when { percent >= 80 -> "Превосходно!"; percent >= 50 -> "Хорошая работа!"; else -> "Попробуйте ещё!" }, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 18.dp))
        Text(quiz.title, color = Color.White.copy(alpha = .9f), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        if (isRecord) Text("✦  Новый личный рекорд", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(vertical = 21.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$score", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Purple); Text("из $max баллов", fontSize = 12.sp, color = Muted) }
            Box(Modifier.width(1.dp).height(46.dp).background(Line))
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$percent%", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink); Text("правильно", fontSize = 12.sp, color = Muted) }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) { ResultPill("Верно", correct, Green, Modifier.weight(1f)); ResultPill("Ошибки", wrong, Red, Modifier.weight(1f)); ResultPill("Пропуск", missed, Muted, Modifier.weight(1f)) }
        Spacer(Modifier.height(22.dp))
        SecondaryButton("Разбор ответов", onReview)
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Пройти заново", onAgain)
        TextButton(onClick = onHome, modifier = Modifier.padding(top = 3.dp)) { Text("Выбрать другой квиз", color = Muted) }
    }
}

@Composable
private fun ReviewScreen(quiz: Quiz, answers: List<Answer>, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp)) {
        BackHeader("Разбор ответов", onBack, light = true)
        Text(quiz.title, color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        if (answers.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Ответов пока нет", color = Muted) }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
            items(quiz.questions.size) { i ->
                val q = quiz.questions[i]
                val a = answers.getOrNull(i)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).padding(16.dp)) {
                    Text("ВОПРОС ${i + 1}", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(q.text, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp, bottom = 10.dp))
                    Text("Ваш ответ: ${a?.selected?.let { q.options.getOrNull(it) } ?: "Пропущен"}", color = if (a?.correct == true) Green else Red, fontSize = 13.sp)
                    Text("Правильный: ${q.options[q.correct]}", color = Green, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    Text("Баллы: ${a?.points ?: 0} из ${q.points}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
                }
            }
        }
    }
}

@Composable
private fun BrandMark(size: androidx.compose.ui.unit.Dp = 54.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(Purple), contentAlignment = Alignment.Center) { Text("?", color = Color.White, fontSize = (size.value * .62f).sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, trailing: Boolean = false) {
    Button(onClick, Modifier.fillMaxWidth().height(54.dp), enabled = enabled, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Purple, disabledContainerColor = Color(0xFFCBC8F5))) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold); if (trailing) { Spacer(Modifier.width(8.dp)); Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(19.dp)) }
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) { Text(text, color = Ink, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit, trailing: String? = null, light: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(42.dp).clip(CircleShape).background(if (light) Color.White.copy(alpha = .18f) else Color.White)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = if (light) Color.White else Ink, modifier = Modifier.size(19.dp)) }
        Text(title, Modifier.weight(1f), color = if (light) Color.White else Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (trailing != null) Text(trailing, modifier = Modifier.width(42.dp), textAlign = TextAlign.End, color = if (light) Color.White else Muted, fontSize = 13.sp) else Spacer(Modifier.width(42.dp))
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier, accent: Color) {
    Row(modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) { Text("✦", color = accent, fontSize = 17.sp) }
        Spacer(Modifier.width(10.dp)); Column { Text(value, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text(label, color = Muted, fontSize = 11.sp) }
    }
}

@Composable
private fun QuizCard(quiz: Quiz, best: Int, onSelect: (Quiz) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White).padding(12.dp)) {
        Text(quiz.title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text("${quiz.questions.size} вопросов", color = Ink, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        if (best > 0) Text("Лучший результат: $best", color = Purple, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
        OutlinedButton(onClick = { onSelect(quiz) }, modifier = Modifier.fillMaxWidth().height(38.dp).padding(top = 3.dp), shape = RoundedCornerShape(7.dp)) { Text("Выбрать", color = Ink, fontSize = 12.sp) }
    }
}

@Composable
private fun AccountScreen(user: String, prefs: android.content.SharedPreferences, onBack: () -> Unit, onLogout: () -> Unit) {
    val history = remember { try { JSONArray(prefs.getString("history", "[]") ?: "[]") } catch (_: Exception) { JSONArray() } }
    val fastest = (0 until history.length()).mapNotNull { history.optJSONObject(it)?.optInt("durationSec")?.takeIf { duration -> duration > 0 } }.minOrNull()
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        BackHeader("Личный кабинет", onBack, light = true)
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White).padding(16.dp)) {
            Text(user.ifBlank { "Игрок" }, color = Ink, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Spacer(Modifier.height(12.dp))
            InfoRow("Всего пройдено викторин", "${history.length()}")
            InfoRow("Самое быстрое прохождение", fastest?.let { "$it сек" } ?: "—")
            if (history.length() == 0) Text("История прохождений пока пуста", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onLogout, Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Red)) { Text("Выйти", color = Red) }
    }
}

@Composable
private fun BackdropDecor() {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val stroke = Color(0xFF26318C).copy(alpha = .24f)
        drawCircle(stroke, radius = 30.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * .08f, size.height * .36f), style = Stroke(2.dp.toPx()))
        drawCircle(stroke, radius = 42.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * .88f, size.height * .83f), style = Stroke(2.dp.toPx()))
        drawCircle(stroke, radius = 18.dp.toPx(), center = androidx.compose.ui.geometry.Offset(size.width * .72f, size.height * .06f), style = Stroke(2.dp.toPx()))
        drawLine(stroke, androidx.compose.ui.geometry.Offset(size.width * .06f, size.height * .18f), androidx.compose.ui.geometry.Offset(size.width * .15f, size.height * .08f), 2.dp.toPx())
        drawLine(stroke, androidx.compose.ui.geometry.Offset(size.width * .15f, size.height * .08f), androidx.compose.ui.geometry.Offset(size.width * .25f, size.height * .16f), 2.dp.toPx())
        drawLine(stroke, androidx.compose.ui.geometry.Offset(size.width * .25f, size.height * .16f), androidx.compose.ui.geometry.Offset(size.width * .34f, size.height * .05f), 2.dp.toPx())
        drawLine(stroke, androidx.compose.ui.geometry.Offset(size.width * .77f, size.height * .30f), androidx.compose.ui.geometry.Offset(size.width * .93f, size.height * .22f), 2.dp.toPx())
    }
}

@Composable
private fun EmptyCard(title: String, message: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Ink, fontWeight = FontWeight.SemiBold); Text(message, color = Muted, textAlign = TextAlign.Center, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)); TextButton(onClick) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(5.dp)); Text(action) }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(label, color = Muted, fontSize = 14.sp); Text(value, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    HorizontalDivider(color = Line)
}

@Composable
private fun ResultPill(label: String, value: Int, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("$value", color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text(label, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)) }
}
