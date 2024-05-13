package com.example.rocketreserver

import android.content.Context
import android.util.Log
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.network.okHttpClient
import kotlinx.coroutines.delay
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.chromium.net.CronetEngine
import org.chromium.net.RequestFinishedInfo
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private class AuthorizationInterceptor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request =
            chain.request().newBuilder()
                .apply {
                    TokenRepository.getToken()?.let { token ->
                        addHeader("Authorization", token)
                    }
                }
                .build()
        return chain.proceed(request)
    }
}

class CronetEngineFactory constructor(
    private val context: Context,
) {
    val cronetEngine: CronetEngine by lazy {
        createCronetEngine()
    }

    val cronetRequestFinishedExecutorService: ExecutorService by lazy {
        Executors.newFixedThreadPool(
            4,
        )
    }

    private fun createCronetEngine(): CronetEngine {
        val builder = CronetEngine.Builder(context)
        val ret = builder.build()
        ret.addRequestFinishedListener(
            CronetEngineRequestFinishedListener(
                cronetRequestFinishedExecutorService,
            ),
        )
        return ret
    }
}

class CronetEngineRequestFinishedListener(executor: Executor?) : RequestFinishedInfo.Listener(executor) {
    override fun onRequestFinished(requestInfo: RequestFinishedInfo?) {
        Log.e("cronetenginerequestfinishedlistener", "cronet request finished: ${requestInfo?.finishedReason}")
    }
}

object ApolloClientFactory {
    var cronetEngine: CronetEngine? = null

    val apolloClient by lazy {
        if (cronetEngine == null) {
            throw IllegalStateException("Initialize Cronet engine first!")
        }
        ApolloClient.Builder()
            .serverUrl("https://apollo-fullstack-tutorial.herokuapp.com/graphql")
            .webSocketServerUrl("wss://apollo-fullstack-tutorial.herokuapp.com/graphql")
            .okHttpClient(
                OkHttpClient.Builder()
                    .addInterceptor(AuthorizationInterceptor())
                    .addInterceptor(
                        // when logging interceptor is at BODY level - everything works fine
                        // when it's at any lower lever (or absent), then:
                        //  - if there's no batching enabled: all requests are reported as cancelled
                        //    - app appears to be functioning correctly, but:
                        //    - however - every cronet request is finished with the "cancelled" status (2)
                        //  - if there's http batching
                        //    - cronet requests never receive finish listeners
                        //    - after a few requests, the app becomes unresponsive
                        //    - (sometimes it recovers with the following logs
                        // - I'm also observing that after a while, following failed request is logged
                        //   (regardless of how cronet/logging-interceprot/batching is configured):
                        //    com.example.rocketreserver           I  <-- HTTP FAILED: java.io.IOException: java.util.concurrent.ExecutionException: org.chromium.net.impl.NetworkExceptionImpl: Exception in CronetUrlRequest: net::ERR_EMPTY_RESPONSE, ErrorCode=11, InternalErrorCode=-324, Retryable=false
                        //    com.example.rocketreserver           E  cronet request finished: 1
                        //    com.example.rocketreserver           D  WebSocket got disconnected, reopening after a delay
                        //  (1 means request failed in cronet status)
                        //  I think this is just a different mode of logging the websocket reconnection
                        //  which seems ok, considering the code below?
                        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY },
                    )
                    .addInterceptor(
                        com.google.net.cronet.okhttptransport.CronetInterceptor.newBuilder(cronetEngine).build(),
                    )
                    .build(),
            )
            .webSocketReopenWhen { throwable, attempt ->
                Log.d("Apollo", "WebSocket got disconnected, reopening after a delay", throwable)
                delay(attempt * 1000)
                true
            }
            .httpBatching(batchIntervalMillis = 10)
            .build()
    }
}
