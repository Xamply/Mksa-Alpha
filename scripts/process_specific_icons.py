import os
from PIL import Image

# Files and their corresponding raw paths
specific_files = [
    {
        "name": "items",
        "raw_path": r"C:\Users\Aly\.gemini\antigravity\brain\a38814bc-728e-4163-af69-9a856b542d85\items_raw_1783053064825.png"
    },
    {
        "name": "armaduras",
        "raw_path": r"C:\Users\Aly\.gemini\antigravity\brain\a38814bc-728e-4163-af69-9a856b542d85\armaduras_raw_1783053121420.png"
    }
]

def process_image(raw_path, output_name):
    if not os.path.exists(raw_path):
        print(f"Error: No se encontró el archivo raw '{raw_path}'")
        return False
        
    img = Image.open(raw_path).convert("RGBA")
    width, height = img.size
    print(f"Procesando {output_name}: tamaño original {width}x{height}")
    
    # Resize to 16x16 with Nearest Neighbor
    resized = img.resize((16, 16), Image.Resampling.NEAREST)
    
    # Key out white background
    pixels = resized.load()
    for y in range(16):
        for x in range(16):
            r, g, b, a = pixels[x, y]
            # Key out very light pixels (white background)
            if r > 240 and g > 240 and b > 240:
                pixels[x, y] = (0, 0, 0, 0)
                
    output_path = os.path.join("minecraft_icons", f"{output_name}.png")
    resized.save(output_path)
    print(f"Icono rediseñado guardado en: '{output_path}'")
    return True

def main():
    os.makedirs("minecraft_icons", exist_ok=True)
    for file_info in specific_files:
        process_image(file_info["raw_path"], file_info["name"])

if __name__ == "__main__":
    main()
