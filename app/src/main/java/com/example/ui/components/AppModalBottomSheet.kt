package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    title: String? = null,
    subtitle: String? = null,
    headerRightContent: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary) },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            if (title != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                color = Lime400,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (headerRightContent != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            headerRightContent()
                        }
                    }
                }
            }
            content()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun BottomSheetActionItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    selected: Boolean = false,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val contentColor = when {
        !enabled -> TextSecondary.copy(alpha = 0.4f)
        destructive -> Red500
        selected -> Lime400
        else -> TextPrimary
    }
    val iconColor = when {
        !enabled -> TextSecondary.copy(alpha = 0.4f)
        destructive -> Red500
        selected -> Lime400
        else -> Lime400
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "$title${if (subtitle != null) ", $subtitle" else ""}${if (selected) ", selecionado" else ""}"
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = contentColor,
                    fontSize = 16.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = if (enabled) TextSecondary else TextSecondary.copy(alpha = 0.4f),
                        fontSize = 12.sp
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selecionado",
                    tint = Lime400,
                    modifier = Modifier.size(20.dp)
                )
            } else if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}

data class ActionItemData(
    val title: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val subtitle: String? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val selected: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionBottomSheet(
    onDismissRequest: () -> Unit,
    title: String,
    actions: List<ActionItemData>,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    AppModalBottomSheet(
        onDismissRequest = onDismissRequest,
        title = title,
        subtitle = subtitle,
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            actions.forEach { action ->
                BottomSheetActionItem(
                    title = action.title,
                    subtitle = action.subtitle,
                    icon = action.icon,
                    enabled = action.enabled,
                    destructive = action.destructive,
                    selected = action.selected,
                    onClick = {
                        onDismissRequest()
                        action.onClick()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SelectionBottomSheet(
    title: String,
    options: List<T>,
    selectedOption: T?,
    optionTitle: (T) -> String,
    onOptionSelected: (T) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    optionSubtitle: ((T) -> String?)? = null,
    optionIcon: ((T) -> ImageVector?)? = null
) {
    AppModalBottomSheet(
        onDismissRequest = onDismissRequest,
        title = title,
        subtitle = subtitle,
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selectedOption
                BottomSheetActionItem(
                    title = optionTitle(option),
                    subtitle = optionSubtitle?.invoke(option),
                    icon = optionIcon?.invoke(option),
                    selected = isSelected,
                    onClick = {
                        onDismissRequest()
                        onOptionSelected(option)
                    }
                )
            }
        }
    }
}
