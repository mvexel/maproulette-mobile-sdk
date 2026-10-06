package org.maproulette.sdk.example

import kotlinx.coroutines.runBlocking
import org.maproulette.sdk.ChallengeId
import org.maproulette.sdk.MapRouletteClient
import org.maproulette.sdk.OkHttpTransport

/** Read-only CLI against production: ./gradlew runExample --args='16441'
 * (optional MAPROULETTE_API_KEY environment variable). */
fun main(args: Array<String>) = runBlocking {
    val id = ChallengeId(args.firstOrNull()?.toLong() ?: 16441L)
    OkHttpTransport().use { transport ->
        val client = MapRouletteClient(transport = transport, apiKey = { System.getenv("MAPROULETTE_API_KEY") })
        val challenge = client.getChallenge(id)
        println("Challenge ${challenge.id.value}: ${challenge.name}")
        client.listTasks(id, pageSize = 2).items.forEach {
            println("Task ${it.id.value}: ${it.status?.knownName ?: "unknown"}")
        }
        if (System.getenv("MAPROULETTE_API_KEY") != null) {
            val user = client.getCurrentUser()
            println("Authenticated: ${!user.guest && user.id > 0}")
        }
    }
}
