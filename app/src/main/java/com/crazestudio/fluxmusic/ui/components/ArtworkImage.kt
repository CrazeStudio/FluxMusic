package com.crazestudio.fluxmusic.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.crazestudio.fluxmusic.R
import kotlin.math.abs

private val FallbackPalettes = listOf(
    listOf(Color(0xFF6F8790), Color(0xFF2B3A3E)),
    listOf(Color(0xFFBA8C75), Color(0xFF443C3B)),
    listOf(Color(0xFF526A66), Color(0xFF2D3C39)),
    listOf(Color(0xFF7B718B), Color(0xFF3E3847)),
    listOf(Color(0xFFC26D4A), Color(0xFF532A1B))
)

@Composable
fun ArtworkImage(
    thumbnailUrl: String,
    trackId: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    iconSize: Dp = 28.dp
) {
    val palette = remember(trackId) {
        val index = abs(trackId.hashCode()) % FallbackPalettes.size
        FallbackPalettes[index]
    }
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(palette)),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnailUrl.isNotBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    VinylFallbackGlyph(iconSize = iconSize)
                },
                error = {
                    VinylFallbackGlyph(iconSize = iconSize)
                }
            )
        } else {
            VinylFallbackGlyph(iconSize = iconSize)
        }
    }
}

@Composable
private fun VinylFallbackGlyph(iconSize: Dp) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_vinyl_placeholder),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.85f)),
            modifier = Modifier.size(iconSize)
        )
    }
}
