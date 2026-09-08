from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/elijah"
GUI = ROOT / "textures/gui/resource_bar.png"
SKIN = ROOT / "textures/entity/undead_pirate_captain.png"


def make_resource_bar():
    sheet = Image.new("RGBA", (256, 48), (0, 0, 0, 0))
    draw = ImageDraw.Draw(sheet)
    # Origins/Apoli resource bars sample a 71x5 background at y=0 and a
    # 71x8 fill at y=8 + bar_index*10. The second row is the crew bar.
    for y, fill in ((0, (36, 36, 42, 235)), (8, (74, 25, 28, 255)),
                    (18, (45, 92, 108, 255))):
        if y == 0:
            draw.rectangle((0, y, 70, y + 4), fill=fill)
            draw.rectangle((1, y + 1, 69, y + 3), fill=(12, 12, 16, 235))
        else:
            draw.rectangle((0, y, 70, y + 7), fill=(18, 18, 22, 235))
            draw.rectangle((1, y + 1, 69, y + 6), fill=fill)
            draw.line((2, y + 1, 68, y + 1), fill=(255, 255, 255, 85), width=1)

    # Bone icon for Dirty Tactics (icon_index 0, x=73).
    bone_x, bone_y = 73, 8
    draw.ellipse((bone_x + 1, bone_y + 2, bone_x + 5, bone_y + 6), fill=(235, 235, 224, 255))
    draw.ellipse((bone_x + 5, bone_y + 1, bone_x + 8, bone_y + 4), fill=(235, 235, 224, 255))
    draw.rectangle((bone_x + 4, bone_y + 3, bone_x + 9, bone_y + 5), fill=(235, 235, 224, 255))
    draw.ellipse((bone_x + 8, bone_y + 4, bone_x + 11, bone_y + 7), fill=(235, 235, 224, 255))

    # A tiny drowned-sailor/skull icon for the crew resource (icon_index 1).
    icon_x, icon_y = 82, 18
    draw.ellipse((icon_x + 1, icon_y + 1, icon_x + 7, icon_y + 7), fill=(220, 229, 220, 255))
    draw.rectangle((icon_x + 2, icon_y + 6, icon_x + 6, icon_y + 8), fill=(220, 229, 220, 255))
    draw.rectangle((icon_x + 2, icon_y + 3, icon_x + 3, icon_y + 4), fill=(25, 35, 38, 255))
    draw.rectangle((icon_x + 5, icon_y + 3, icon_x + 6, icon_y + 4), fill=(25, 35, 38, 255))
    draw.line((icon_x + 2, icon_y + 6, icon_x + 6, icon_y + 6), fill=(25, 35, 38, 255), width=1)
    sheet.save(GUI)


def make_pirate_skin():
    # A compact 64x64 classic-arm undead pirate texture. The in-game renderer
    # uses this as a stable bundled fallback for the linked reference skin.
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    skin = (112, 145, 118, 255)
    shadow = (68, 91, 78, 255)
    coat = (31, 38, 53, 255)
    coat_light = (52, 61, 82, 255)
    red = (120, 36, 42, 255)
    gold = (195, 157, 62, 255)
    bone = (204, 197, 166, 255)
    dark = (20, 19, 24, 255)

    # Head front / back / sides.
    draw.rectangle((8, 8, 15, 15), fill=skin)
    draw.rectangle((10, 8, 13, 10), fill=dark)  # hat brim shadow
    draw.rectangle((10, 11, 11, 12), fill=dark)
    draw.rectangle((13, 11, 14, 12), fill=dark)
    draw.point((12, 14), fill=bone)
    draw.rectangle((0, 8, 7, 15), fill=shadow)
    draw.rectangle((16, 8, 23, 15), fill=shadow)
    draw.rectangle((8, 0, 15, 7), fill=dark)
    draw.rectangle((8, 16, 15, 23), fill=shadow)
    # Tricorne hat and bandana across the head.
    draw.rectangle((7, 6, 16, 8), fill=dark)
    draw.rectangle((9, 4, 14, 6), fill=dark)
    draw.rectangle((8, 7, 15, 8), fill=red)
    draw.rectangle((11, 7, 12, 8), fill=gold)

    # Torso: dark coat, red sash, gold buttons.
    draw.rectangle((20, 20, 27, 31), fill=coat)
    draw.rectangle((28, 20, 35, 31), fill=coat_light)
    draw.rectangle((36, 20, 43, 31), fill=coat)
    draw.rectangle((44, 20, 51, 31), fill=coat_light)
    draw.rectangle((20, 24, 35, 26), fill=red)
    draw.rectangle((28, 24, 29, 26), fill=gold)
    draw.rectangle((32, 24, 33, 26), fill=gold)
    draw.rectangle((34, 20, 35, 31), fill=dark)
    # Back and side torso regions.
    draw.rectangle((20, 16, 35, 19), fill=dark)
    draw.rectangle((36, 16, 51, 19), fill=dark)
    draw.rectangle((20, 32, 35, 35), fill=coat)
    draw.rectangle((36, 32, 51, 35), fill=coat)

    # Arms: coat sleeves with bone hands.
    for x in (44, 52):
        draw.rectangle((x, 20, x + 3, 31), fill=coat)
        draw.rectangle((x + 4, 20, x + 7, 31), fill=coat_light)
        draw.rectangle((x, 32, x + 3, 35), fill=skin)
        draw.rectangle((x + 4, 32, x + 7, 35), fill=shadow)
    # Legs and boots.
    draw.rectangle((4, 20, 11, 31), fill=coat)
    draw.rectangle((12, 20, 19, 31), fill=coat_light)
    draw.rectangle((4, 32, 11, 35), fill=dark)
    draw.rectangle((12, 32, 19, 35), fill=dark)
    draw.rectangle((4, 36, 11, 47), fill=coat)
    draw.rectangle((12, 36, 19, 47), fill=coat_light)
    draw.rectangle((4, 44, 11, 47), fill=dark)
    draw.rectangle((12, 44, 19, 47), fill=dark)
    # Extra-layer areas are transparent but keep a few pirate accents.
    draw.rectangle((40, 36, 47, 39), fill=red)
    draw.rectangle((48, 36, 55, 39), fill=gold)
    image.save(SKIN)


if __name__ == "__main__":
    GUI.parent.mkdir(parents=True, exist_ok=True)
    SKIN.parent.mkdir(parents=True, exist_ok=True)
    make_resource_bar()
    make_pirate_skin()
