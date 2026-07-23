from PIL import Image
img = Image.open(r"e:\my_projects\AndroidStudioProjects\ta\r1.png").convert("RGB")
print(f"image: {img.size}")
print("\n=== 卡片底色采样 ===")
for (x, y) in [(90, 2200), (1100, 2200), (90, 2350), (1100, 2500), (90, 2600)]:
    print(f"  ({x},{y}) -> RGB{img.getpixel((x,y))}")
print("\n=== 期望 ===")
print("  底色 #FF0000 = RGB(255,0,0)")
