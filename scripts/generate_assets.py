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
    # A 64x64 classic-arm undead pirate texture based on the attached captain
    # reference: a pale skull, green-gold captain hat, red coat and dark boots.
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    bone = (222, 216, 174, 255)
    bone_shadow = (169, 157, 113, 255)
    eye = (24, 20, 20, 255)
    hat = (166, 176, 45, 255)
    hat_shadow = (93, 104, 29, 255)
    red = (126, 22, 24, 255)
    red_shadow = (73, 14, 20, 255)
    gold = (220, 164, 42, 255)
    leather = (86, 43, 20, 255)
    steel = (83, 91, 91, 255)
    dark = (28, 24, 30, 255)

    def box(coords, color):
        draw.rectangle(coords, fill=color)

    # Head faces: skull and the green-gold captain hat.
    for coords, color in [
        ((8, 8, 15, 15), bone), ((24, 8, 31, 15), bone_shadow),
        ((0, 8, 7, 15), bone_shadow), ((16, 8, 23, 15), bone_shadow),
        ((8, 0, 15, 7), hat), ((16, 0, 23, 7), hat_shadow),
        ((8, 16, 15, 23), bone_shadow), ((16, 16, 23, 23), dark)
    ]:
        box(coords, color)
    box((8, 6, 15, 8), hat_shadow)
    box((10, 6, 13, 7), hat)
    box((9, 10, 10, 11), eye)
    box((13, 10, 14, 11), eye)
    box((11, 13, 12, 14), eye)
    box((10, 15, 13, 15), bone_shadow)

    # Torso front/back/sides: red captain's coat with a gray throat guard.
    for coords in ((20, 20, 27, 31), (32, 20, 39, 31), (16, 20, 19, 31), (28, 20, 31, 31)):
        box(coords, red)
    box((20, 20, 27, 22), red_shadow)
    box((23, 20, 24, 25), steel)
    box((20, 27, 27, 29), leather)
    box((20, 28, 27, 28), gold)
    for x in (21, 25):
        box((x, 24, x, 25), gold)
    box((32, 20, 39, 22), red_shadow)
    box((32, 27, 39, 29), leather)
    box((32, 28, 39, 28), gold)
    box((20, 16, 27, 19), gold)
    box((28, 16, 35, 19), leather)
    box((20, 32, 27, 35), red_shadow)
    box((32, 32, 39, 35), red_shadow)

    # Classic 4px arms: red sleeves, gold cuffs and bone hands. Each model
    # face is filled so no transparent UV face renders as a black placeholder.
    for front_x, y in ((44, 20), (36, 52)):
        for x, shade in ((front_x, red), (front_x - 4, red_shadow),
                         (front_x + 4, red_shadow), (front_x + 8, red_shadow)):
            box((x, y, x + 3, y + 11), shade)
        box((front_x, y + 9, front_x + 3, y + 11), gold)
        box((front_x, y + 12, front_x + 3, y + 15), bone)
        box((front_x + 4, y + 12, front_x + 7, y + 15), bone_shadow)
        box((front_x, y - 4, front_x + 3, y - 1), gold)
        box((front_x + 4, y - 4, front_x + 7, y - 1), leather)

    # Legs: dark trousers, brown boots and red coat tails; fill front, back
    # and side faces for both right and left legs.
    for front_x, y in ((4, 20), (20, 52)):
        for x, shade in ((front_x, dark), (front_x + 8, red_shadow),
                         (front_x - 4, dark), (front_x + 4, dark)):
            box((x, y, x + 7, y + 11), shade)
        box((front_x, y + 8, front_x + 7, y + 9), leather)
        box((front_x, y + 10, front_x + 7, y + 11), gold)
        box((front_x, y - 4, front_x + 7, y - 1), red_shadow)
        box((front_x + 8, y - 4, front_x + 15, y - 1), leather)
    image.save(SKIN)


if __name__ == "__main__":
    GUI.parent.mkdir(parents=True, exist_ok=True)
    SKIN.parent.mkdir(parents=True, exist_ok=True)
    make_resource_bar()
    # The exact 64x64 captain skin is checked in separately from the generated
    # bar sheet; do not overwrite it with the optional fallback generator.
