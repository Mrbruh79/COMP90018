package com.example.blap.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val CommonGroundShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp), // icon chips
    small = RoundedCornerShape(14.dp), // text inputs
    medium = RoundedCornerShape(16.dp), // cards / buttons
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
