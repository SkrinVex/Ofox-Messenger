package com.SkrinVex.OfoxMessenger.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import java.io.Serializable

interface ApiService {
    @Multipart
    @POST("Ofox/images/image.php")
    suspend fun uploadImage(
        @Part image: MultipartBody.Part,
        @Part("user_id") userId: RequestBody,
        @Part("type") type: RequestBody
    ): Response<ImageUploadResponse>

    @GET("Ofox/main_page")
    suspend fun getMainPageData(
        @Query("user_id") userId: String
    ): Response<MainPageResponse>

    @GET("Ofox/friends")
    suspend fun getFriends(
        @Query("api_key") apiKey: String,
        @Query("search_query") searchQuery: String?
    ): Response<FriendsResponse>

    companion object {
        private const val BASE_URL = "https://api.skrinvex.su/"

        fun create(): ApiService {
            val retrofit = retrofit2.Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            return retrofit.create(ApiService::class.java)
        }
    }
}

data class ImageUploadResponse(
    val success: Boolean,
    val message: String? = null,
    val image_url: String? = null,
    val error: String? = null
) : Serializable

data class MainPageResponse(
    val success: Boolean,
    val profile: ProfileCheckResponse? = null,
    val news_feed: List<NewsItem>,
    val notifications: List<NotificationItem>,
    val error: String? = null
) : Serializable

data class ProfileCheckResponse(
    val success: Boolean = true,
    val user_id: String? = null,
    val username: String? = null,
    val nickname: String? = null,
    val email: String? = null,
    val birthday: String? = null,
    val status: String? = null,
    val bio: String? = null,
    val profile_photo: String? = null,
    val background_photo: String? = null,
    val profile_completion: Int = 0,
    val friendship_status: String? = null,
    val is_friend: Boolean = false,
    val error: String? = null
) : Serializable

data class FriendsResponse(
    val success: Boolean = true,
    val friends: List<Friend> = emptyList(),
    val friends_count: Int = 0,
    val other_users: List<Friend> = emptyList(),
    val other_users_count: Int = 0,
    val error: String? = null
) : Serializable

data class Friend(
    val id: String,
    val username: String,
    val nickname: String? = null,
    val status: String? = null,
    val profile_photo: String? = null
) : Serializable

data class NewsItem(
    val title: String,
    val date: String,
    val content: String,
    val image_url: String? = null
)

data class NotificationItem(
    val id: String? = null,
    val title: String,
    val created_at: String,
    val content: String,
    val from_uid: String? = null
) : Serializable

data class PostItem(
    val id: String? = null,
    val user_id: String,
    val username: String? = null,
    val nickname: String? = null,
    val profile_photo: String? = null,
    val title: String,
    val content: String,
    val image_url: String? = null,
    val created_at: String
) : Serializable

data class GenericResponse(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null
) : Serializable

data class ChatsResponse(
    val success: Boolean,
    val chats: List<ChatItem> = emptyList(),
    val error: String? = null
) : Serializable

data class ChatItem(
    val chat_id: String,
    val friend_id: String,
    val friend_username: String,
    val friend_nickname: String? = null,
    val friend_photo: String? = null,
    val last_message: String? = null,
    val last_message_time: Long? = null,
    val unread_count: Int = 0
) : Serializable