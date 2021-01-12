package com.harshit.musicstreaming1.ui

import android.Manifest
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.RequestManager
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Task
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.UploadTask
import com.harshit.musicstreaming1.R
import com.harshit.musicstreaming1.adapters.SwipeSongAdapter
import com.harshit.musicstreaming1.data.entities.Song
import com.harshit.musicstreaming1.exoplayer.isPlaying
import com.harshit.musicstreaming1.exoplayer.toSong
import com.harshit.musicstreaming1.other.Status.*
import com.harshit.musicstreaming1.ui.viewmodels.MainViewModel
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    @Inject
    lateinit var swipeSongAdapter: SwipeSongAdapter

    @Inject
    lateinit var glide: RequestManager

    private var curPlayingSong: Song? = null

    private var playbackState: PlaybackStateCompat? = null

    var uri: Uri? = null
    lateinit var songName: String
    lateinit var songUrl: String
    var checkPermission = false
    lateinit var songsLoad: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        subscribeToObservers()



        vpSong.adapter = swipeSongAdapter

        vpSong.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (playbackState?.isPlaying == true) {
                    mainViewModel.playOrToggleSong(swipeSongAdapter.songs[position])
                } else {
                    curPlayingSong = swipeSongAdapter.songs[position]
                }
            }
        })

        imgPlayPause.setOnClickListener {
            curPlayingSong?.let {
                mainViewModel.playOrToggleSong(it, true)
            }
        }

        swipeSongAdapter.setItemClickListener {
            navHostFragment.findNavController().navigate(
                R.id.globalActionToSongFragment
            )
        }

        navHostFragment.findNavController().addOnDestinationChangedListener { _, destination, _ ->
            when(destination.id) {
                R.id.songFragment -> hideBottomBar()
                R.id.homeFragment -> showBottomBar()
                else -> showBottomBar()
            }
        }

        imgLogo.setOnClickListener {
            if(validatePermission()){
                pickSong()
            }
        }
    }

    private fun pickSong() {
        val uploadIntent = Intent(Intent.ACTION_GET_CONTENT)
        uploadIntent.type = "audio/*"
        startActivityForResult(uploadIntent, 1)
    }

    private fun validatePermission(): Boolean {
        Dexter.withActivity(this)
            .withPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            .withListener(object : PermissionListener {
                override fun onPermissionGranted(response: PermissionGrantedResponse) {
                    checkPermission = true
                }

                override fun onPermissionDenied(response: PermissionDeniedResponse) {
                    checkPermission = false
                }

                override fun onPermissionRationaleShouldBeShown(
                    permission: PermissionRequest?,
                    token: PermissionToken
                ) {
                    token.continuePermissionRequest()
                }
            }).check()
        return checkPermission
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {

        if(requestCode==1){
            if (resultCode== RESULT_OK){
                uri = data?.data
                val c: Cursor? = applicationContext.contentResolver.query(
                    uri!!,
                    null,
                    null,
                    null,
                    null
                )
                val indexName = c?.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                c?.moveToFirst()
                songName = c?.getString(indexName!!).toString()
                c?.close()

                uploadSongsToStorage()

            }
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    fun uploadSongsToStorage() {
        val storageReference: StorageReference = FirebaseStorage.getInstance().getReference().child(
            "songs"
        ).child(
            uri?.lastPathSegment.toString()
        )

        storageReference.putFile(uri!!).addOnSuccessListener(OnSuccessListener<UploadTask.TaskSnapshot>() {

            @Override
            fun onSuccess(taskSnapshot: UploadTask.TaskSnapshot){
                val uriTask: Task<Uri> = taskSnapshot.storage.downloadUrl
                while(!uriTask.isComplete){
                    val urlSong = uriTask.result
                    songUrl = urlSong.toString()
                    uploadDetailsToFirestore()}
                Toast.makeText(this, "Success", Toast.LENGTH_SHORT).show()
        }


        }).addOnFailureListener(OnFailureListener() {
            Toast.makeText(this, "failure", Toast.LENGTH_SHORT).show()
            songsLoad.visibility = View.GONE
        })

    }

    private fun uploadDetailsToFirestore(){

        val db = FirebaseFirestore.getInstance()
        val song = db.collection("songs")

        GlobalScope.launch {
            val songs = Song("10", songName, "Unknown", songUrl, "unkown")
            song.document("songs").set(songs, SetOptions.merge())
        }


    }

    private fun hideBottomBar() {
        leftView.isVisible = false
        rightView.isVisible = false
        imgLogo.isVisible = false
        flBar.isVisible = false
        imgSongIMage.isVisible = false
        vpSong.isVisible = false
        imgPlayPause.isVisible = false
    }

    private fun showBottomBar() {
        leftView.isVisible = true
        rightView.isVisible = true
        imgLogo.isVisible = true
        flBar.isVisible = true
        imgSongIMage.isVisible = true
        vpSong.isVisible = true
        imgPlayPause.isVisible = true
    }

    private fun switchViewPagerToCurrentSong(song: Song) {
        val newItemIndex = swipeSongAdapter.songs.indexOf(song)
        if (newItemIndex != -1) {
            vpSong.currentItem = newItemIndex
            curPlayingSong = song
        }
    }

    private fun subscribeToObservers() {
        mainViewModel.mediaItems.observe(this) {
            it?.let { result ->
                when (result.status) {
                    SUCCESS -> {
                        result.data?.let { songs ->
                            swipeSongAdapter.songs = songs
                            if (songs.isNotEmpty()) {
                                glide.load((curPlayingSong ?: songs[0]).imageUrl)
                                    .into(imgSongIMage)
                            }
                            switchViewPagerToCurrentSong(curPlayingSong ?: return@observe)
                        }
                    }
                    ERROR -> Unit
                    LOADING -> Unit
                }
            }
        }
        mainViewModel.curPlayingSong.observe(this) {
            if (it == null) return@observe

            curPlayingSong = it.toSong()
            glide.load(curPlayingSong?.imageUrl).into(imgSongIMage)
            switchViewPagerToCurrentSong(curPlayingSong ?: return@observe)
        }
        mainViewModel.playbackState.observe(this) {
            playbackState = it
            imgPlayPause.setImageResource(
                if (playbackState?.isPlaying == true) R.drawable.icon_pause else R.drawable.icon_play
            )
        }
        mainViewModel.isConnected.observe(this) {
            it?.getContentIfNotHandled()?.let { result ->
                when (result.status) {
                    ERROR -> Snackbar.make(
                        rootLayout,
                        result.message ?: "An unknown error occured",
                        Snackbar.LENGTH_LONG
                    ).show()
                    else -> Unit
                }
            }
        }
        mainViewModel.networkError.observe(this) {
            it?.getContentIfNotHandled()?.let { result ->
                when (result.status) {
                    ERROR -> Snackbar.make(
                        rootLayout,
                        result.message ?: "An unknown error occured",
                        Snackbar.LENGTH_LONG
                    ).show()
                    else -> Unit
                }
            }
        }
    }
}























