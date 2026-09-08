package git.shin.animevsub.utils

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the existing maintenance/active-state check used by MainActivity.
 * App self-update checking/downloading has intentionally been removed.
 */
@Singleton
class UpdateManager @Inject constructor(
  @ApplicationContext private val context: Context,
  private val client: OkHttpClient
) {
  private val activeCheckUrl =
    "https://raw.githubusercontent.com/anime-vsub/app/refs/heads/main/native-active"

  suspend fun checkAppActive(): Result<Boolean> = withContext(Dispatchers.IO) {
    try {
      val request = Request.Builder()
        .url(activeCheckUrl)
        .build()

      val response = client.newCall(request).execute()
      response.use { res ->
        if (res.isSuccessful) {
          val body = res.body.string().trim()
          Result.success(body.isEmpty())
        } else {
          Result.success(false)
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }
}
