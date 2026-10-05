"""What all five weapon models share."""

# The models stand upright; these are vanilla's sword and axe positions (item/handheld and
# item/generated) with the model first turned 45 degrees onto the diagonal. The inventory view is
# tilted a little so the depth shows. Left-hand values are the right-hand ones mirrored, which is
# how Minecraft reads them.
DISPLAY = {
    'thirdperson_righthand': {'rotation': [0, -90, 10], 'translation': [0, 4.0, 0.5], 'scale': [0.85, 0.85, 0.85]},
    'thirdperson_lefthand': {'rotation': [0, 90, -10], 'translation': [0, 4.0, 0.5], 'scale': [0.85, 0.85, 0.85]},
    'firstperson_righthand': {'rotation': [0, -90, -20], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'firstperson_lefthand': {'rotation': [0, 90, 20], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'ground': {'rotation': [0, 0, -45], 'translation': [0, 2, 0], 'scale': [0.5, 0.5, 0.5]},
    'head': {'rotation': [0, 180, -45], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
    'fixed': {'rotation': [0, 180, -45], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
    'gui': {'rotation': [12, -22, -45], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
}

# The showcase view: the weapon upright, turned to show its depth.
SHOWCASE = (14, -32, 0)
