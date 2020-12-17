package com.lama

import com.lama.domain.ChangeGameStateCommand
import com.lama.domain.ClientCommand
import com.lama.domain.CreateGameCommand
import com.lama.domain.GameNotFoundException
import com.lama.domain.GameStateMessage
import com.lama.domain.GameUpdateException
import com.lama.domain.JoinGameCommand
import com.lama.domain.LeaveGameCommand
import com.lama.domain.PickAnswerCommand
import com.lama.domain.PlayerJoinedMessage
import com.lama.domain.ServerMessage
import com.lama.service.PlayerGateway
import java.lang.Integer.toHexString
import kotlin.random.Random.Default.nextInt

interface GameService {
    fun startGame(quizzId: QuizzId, hostId: PlayerId?): Game
    fun get(gameId: GameId): Game
    fun update(gameId: GameId, stateChange: StateChange): Game
    fun getHighScore(gameId: GameId, limit: Int): HighScore

    fun handle(playerId: PlayerId, command: ClientCommand)
}

class GameServiceImpl(
    private val quizzService: QuizzService,
    private val playerGateway: PlayerGateway
) : GameService {
    private val gameStorage = mutableMapOf<GameId, Game>()
    private val playersStorage = mutableMapOf<PlayerId, Player>()

    override fun startGame(quizzId: QuizzId, hostId: PlayerId?): Game {
        val quizz = quizzService.get(quizzId)
        val game = Game(GameId(nextId()), quizz, quizz.questions.first().id, GameState.QUESTION, hostId)
        gameStorage[game.id] = game
        if (hostId != null) {
            playerGateway.send(hostId, getMessage(game, null))
        }
        return game
    }

    override fun get(gameId: GameId): Game =
        gameStorage[gameId] ?: throw GameNotFoundException(gameId)

    override fun update(gameId: GameId, stateChange: StateChange): Game {
        val game = get(gameId)
        if (game.quizz.questions.none { it.id == stateChange.questionId }) {
            throw GameUpdateException("Question ${stateChange.questionId} does not belong to game $gameId")
        }
        game.currentQuestionId = stateChange.questionId
        game.state = stateChange.state

        game.playerIds.mapNotNull { playersStorage[it] }.forEach {
            playerGateway.send(it.id, getMessage(game, it))
        }
        //        TODO: clean up the game after finish
        return game
    }

    private fun getMessage(game: Game, player: Player?): ServerMessage {
        val currentQuestion = game.getCurrentQuestion()
        val rightAnswerIds = currentQuestion?.answers?.filter { it.isRight }?.map { it.id }
        return GameStateMessage(
            game.id,
            game.state,
            game.quizz.title,
            currentQuestion,
            rightAnswerIds,
            player?.lastAnswerId
        )
    }

    override fun getHighScore(gameId: GameId, limit: Int): HighScore {
        val top = get(gameId).playerIds
            .mapNotNull(playersStorage::get)
            .sortedByDescending { it.score }
            .take(limit)
            .map { PlayerScore(it.name, it.score) }
        return HighScore(top)
    }

    @Suppress("IMPLICIT_CAST_TO_ANY")
    override fun handle(playerId: PlayerId, command: ClientCommand) {
        val res = when (command) {
            is JoinGameCommand -> joinGame(command.gameId, playerId, command.name)
            is PickAnswerCommand -> pickAnswer(playerId, command.questionId, command.answerId)
            is LeaveGameCommand -> leaveGame(playerId)
            is CreateGameCommand -> startGame(command.quizzId, playerId)
            is ChangeGameStateCommand -> update(command.gameId, StateChange(command.questionId, command.state))
        }
    }

    private fun joinGame(gameId: GameId, playerId: PlayerId, name: String) {
        val game = get(gameId)
        val newPlayer = Player(playerId, name, gameId, 0, null, null)
        game.playerIds += playerId
        playersStorage[playerId] = newPlayer
        playerGateway.send(playerId, getMessage(game, newPlayer))
        if (game.hostId != null) {
            playerGateway.send(game.hostId, PlayerJoinedMessage(newPlayer.id, newPlayer.name))
        }
//        TODO: handle errors
    }

    private fun pickAnswer(playerId: PlayerId, questionId: QuestionId, answerId: AnswerId) {
        val player = playersStorage[playerId]!!
        player.lastQuestionId = questionId
        player.lastAnswerId = answerId
        val currentQuestion = get(player.gameId).getCurrentQuestion()
        if (questionId == currentQuestion?.id && currentQuestion.isRight(answerId)) {
            player.score++
        }
    }

    private fun leaveGame(playerId: PlayerId) {
        val player = playersStorage.remove(playerId)
        val game = player.let { gameStorage[player?.gameId] }
        game?.playerIds?.remove(playerId)
    }
}

private fun Question.isRight(answerId: AnswerId): Boolean = answers.find { it.id == answerId }?.isRight ?: false

fun Game.getCurrentQuestion(): Question? = quizz.questions.find { it.id == currentQuestionId }

fun nextId(): String = toHexString(nextInt()).toString()

data class Player(
    val id: PlayerId,
    val name: String,
    val gameId: GameId,
    var score: Int,
    var lastQuestionId: QuestionId?,
    var lastAnswerId: AnswerId?
)

