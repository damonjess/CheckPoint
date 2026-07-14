package com.yourcompany.facesearch.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.yourcompany.facesearch.R
import java.io.File
import java.io.FileOutputStream

class FaceSearchConfirmFragment : Fragment() {

    private val viewModel: CheckInViewModel by viewModels()
    private var croppedBitmap: Bitmap? = null
    private var myNameHint: String? = null

    companion object {
        private const val ARG_CROPPED_BITMAP_PATH = "cropped_path"
        private const val ARG_NAME_HINT = "name_hint"

        fun newInstance(croppedBitmap: Bitmap, nameHint: String? = null): FaceSearchConfirmFragment {
            val fragment = FaceSearchConfirmFragment()
            val args = Bundle().apply {
                // We'll need a way to pass the bitmap. A simple temp file works.
                // Note: In a real app, you'd use a better way to share data between screens.
                putString(ARG_NAME_HINT, nameHint)
            }
            fragment.arguments = args
            // We can't easily save the bitmap here without a context, 
            // so we'll pass it via a singleton or a more complex way if using Fragments.
            // For this project, we've implemented the Compose version which is much simpler.
            fragment.tempBitmap = croppedBitmap 
            return fragment
        }
    }

    // Temporary storage for the bitmap since Fragments + Compose interop can be tricky
    var tempBitmap: Bitmap? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_face_search_confirm, container, false)

        val tvName: TextView = view.findViewById(R.id.tv_name_hint)
        val btnSearch: Button = view.findViewById(R.id.btn_start_search)
        val btnCancel: Button = view.findViewById(R.id.btn_cancel)

        myNameHint = arguments?.getString(ARG_NAME_HINT)
        croppedBitmap = tempBitmap

        tvName.text = myNameHint ?: "Target: Anonymous"

        preview(view)

        val btnFreeSearch: Button = view.findViewById(R.id.btn_free_search)
        btnFreeSearch.setOnClickListener {
            viewModel.setSearchMode(SearchMode.FREE)
            showPhotoSourceDialog()
        }

        btnSearch.setOnClickListener {
            croppedBitmap?.let { bitmap ->
                viewModel.onImageReady(bitmap, myNameHint)
                Toast.makeText(requireContext(), "Opening search engines...", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }
        }

        btnCancel.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        return view
    }

    private fun saveBitmapTemp(bitmap: Bitmap): File {
        val file = File(requireContext().cacheDir, "temp_face_crop.jpg")
        
        FileOutputStream(file).use {
            // Use 85 quality instead of 90 to save memory
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
        }
        return file
    }

    private fun preview(view: View) {
        val bitmap = croppedBitmap ?: return
        val file = saveBitmapTemp(bitmap)
        val imagePreview: ImageView = view.findViewById(R.id.iv_face_preview)
        imagePreview.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
    }

    private fun showPhotoSourceDialog() {
        val options = arrayOf("Take New Photo", "Choose from Gallery")
        
        AlertDialog.Builder(requireContext())
            .setTitle("Select Photo Source")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.startCamera()
                    1 -> viewModel.openGallery()
                }
            }
            .show()
    }
}
