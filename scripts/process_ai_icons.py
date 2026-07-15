import os
from PIL import Image

# Files and their corresponding raw paths
ai_files = [
    {
        "name": "logros_ai",
        "raw_path": r"C:\Users\Aly\.gemini\antigravity\brain\a38814bc-728e-4163-af69-9a856b542d85\logros_raw_1783048631871.png"
    },
    {
        "name": "herramientas_ai",
        "raw_path": r"C:\Users\Aly\.gemini\antigravity\brain\a38814bc-728e-4163-af69-9a856b542d85\herramientas_raw_1783048640448.png"
    },
    {
        "name": "estructuras_ai",
        "raw_path": r"C:\Users\Aly\.gemini\antigravity\brain\a38814bc-728e-4163-af69-9a856b542d85\estructuras_raw_1783048648110.png"
    }
]

def process_image(raw_path, output_name):
    if not os.path.exists(raw_path):
        print(f"Error: No se encontró el archivo raw '{raw_path}'")
        return False
        
    img = Image.open(raw_path).convert("RGBA")
    
    # 1. We will crop the center region slightly to reduce margins if there is empty white space.
    # Most generated images are centered. Let's see the dimensions first.
    width, height = img.size
    print(f"Procesando {output_name}: tamaño original {width}x{height}")
    
    # Crop slightly to focus on the icon if needed, or resize directly.
    # Since the generator created a centered icon, resizing to 16x16 with Nearest Neighbor
    # will maintain clean pixel boundaries.
    resized = img.resize((16, 16), Image.Resampling.NEAREST)
    
    # 2. Key out the solid white background to make it transparent
    pixels = resized.load()
    for y in range(16):
        for x in range(16):
            r, g, b, a = pixels[x, y]
            # If the pixel is very close to white, make it transparent
            if r > 240 and g > 240 and b > 240:
                pixels[x, y] = (0, 0, 0, 0)
                
    output_path = os.path.join("minecraft_icons", f"{output_name}.png")
    resized.save(output_path)
    print(f"Icono procesado con IA guardado en: '{output_path}'")
    return True

def main():
    os.makedirs("minecraft_icons", exist_ok=True)
    for file_info in ai_files:
        process_image(file_info["raw_path"], file_info["name"])

if __name__ == "__main__":
    main()
