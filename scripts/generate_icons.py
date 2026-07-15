import os
from PIL import Image

# Define the icons, their palettes, and their pixel grids
icons_data = [
    {
        "name": "items",
        "description": "Diamond (Diamante)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (15, 30, 40, 255),      # Dark outline
            "w": (220, 255, 255, 255),   # White shine
            "c": (100, 220, 255, 255),   # Cyan highlight
            "d": (50, 180, 220, 255),    # Medium cyan
            "b": (20, 110, 150, 255),    # Dark cyan shadow
        },
        "grid": [
            "......kkkk......",
            "....kkcddcckk...",
            "...kcwdddddbbk..",
            "..kcwdddddddbbk.",
            "..kdddddddddbbk.",
            ".kcdddddddddbbbk",
            ".kddddddddbbbbbk",
            ".kddddddbbbbbbbk",
            ".kbddbbbbbbbbbk.",
            "..kbbbbbbbbbbk..",
            "..kbbbbbbbbbk...",
            "...kbbbbbbbk....",
            "....kbbbbbk.....",
            ".....kbbbk......",
            "......kbk.......",
            ".......k........"
        ]
    },
    {
        "name": "bloques",
        "description": "Grass Block Face (Cara de Bloque de Césped)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (30, 20, 15, 255),      # Dark brown outline
            "g": (90, 180, 50, 255),     # Medium green grass
            "h": (120, 220, 70, 255),    # Light green grass
            "e": (60, 130, 30, 255),     # Dark green grass
            "d": (120, 85, 55, 255),     # Medium dirt brown
            "s": (150, 110, 75, 255),    # Light dirt brown
            "c": (90, 60, 40, 255),      # Dark dirt brown
            "p": (140, 140, 140, 255),   # Stone pebbles
        },
        "grid": [
            "kkkkkkkkkkkkkkkk",
            "khhhhhhhhhhhhhhk",
            "khgggggggggggghk",
            "khgeeeegeeeeeghk",
            "kgeedeeeedeeeedk",
            "kedddddddddddddk",
            "kdddsddddpdddsdk",
            "kddddddddddddddk",
            "kddcddddddcddddk",
            "kddddddpdddddddk",
            "kdddsddddddddsdk",
            "kdddddddddcddddk",
            "kddpddddddddpddk",
            "kddddcdddddddddk",
            "kddddddddddddddk",
            "kkkkkkkkkkkkkkkk"
        ]
    },
    {
        "name": "entidades",
        "description": "Creeper Face (Cara de Creeper)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (15, 35, 15, 255),      # Dark green outline
            "g": (80, 205, 75, 255),     # Creeper green
            "h": (115, 230, 110, 255),    # Creeper highlight
            "d": (50, 150, 45, 255),     # Creeper shadow
            "E": (20, 25, 20, 255),      # Black eyes/mouth
        },
        "grid": [
            "................",
            "..kkkkkkkkkkkk..",
            "..khhhhhhhhhhk..",
            "..khgEEggEEgdk..",
            "..khgEEggEEgdk..",
            "..khgggEEggddk..",
            "..khggEEEEggdk..",
            "..khggEEEEggdk..",
            "..khggEgEgggdk..",
            "..khggggggggdk..",
            "..kddddddddddk..",
            "..kkkkkkkkkkkk..",
            "................",
            "................",
            "................",
            "................"
        ]
    },
    {
        "name": "biomas",
        "description": "Mini Tree Biome (Bioma de Árbol Mini)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (15, 20, 15, 255),      # Dark outline
            "b": (160, 220, 255, 255),   # Sky blue
            "g": (70, 180, 60, 255),     # Leaves green
            "h": (110, 215, 90, 255),    # Highlight leaves
            "d": (40, 120, 35, 255),     # Shadow leaves
            "w": (120, 80, 50, 255),     # Trunk brown
            "s": (90, 55, 30, 255),      # Dark trunk shadow
            "e": (110, 75, 40, 255),     # Dirt brown
        },
        "grid": [
            "....kkkkkkkk....",
            "..kkbbhghhbbkk..",
            ".kbbhggggghhbbk.",
            ".kbhggggggghhbk.",
            "kbggggggghhghdbk",
            "kbgggggghhdhhdbk",
            "kbgggdghhdddddbk",
            ".kbgdddddddddbk.",
            "..kkkgwwskkkk...",
            "....kwwskk......",
            "....kwwskk......",
            "...kwwwskk......",
            ".kkkwwwskkkkk...",
            "kghgghhghhgghk..",
            "keeeeeeeeeeeek..",
            "kkkkkkkkkkkkkk.."
        ]
    },
    {
        "name": "dimensiones",
        "description": "Nether Portal (Portal del Nether)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (10, 5, 15, 255),       # Dark outline
            "o": (30, 20, 45, 255),      # Obsidian base
            "u": (65, 35, 90, 255),      # Light obsidian
            "p": (160, 50, 220, 255),    # Light portal purple
            "m": (110, 30, 160, 255),    # Medium portal purple
            "d": (70, 15, 110, 255),     # Dark portal purple
            "w": (240, 180, 255, 255),   # Neon portal highlight
        },
        "grid": [
            "..kkkkkkkkkkkk..",
            "..koooouuuuook..",
            "..kouppppppuok..",
            "..kopmwwmppdok..",
            "..kopwwmppddok..",
            "..kopwmppdddok..",
            "..kopmppddddok..",
            "..koppddddmdok..",
            "..kopddddmmdok..",
            "..kopddmmmppok..",
            "..kopdmmmpppok..",
            "..kopmmmppppok..",
            "..kouppppppuok..",
            "..koooouuuuook..",
            "..kkkkkkkkkkkk..",
            "................"
        ]
    },
    {
        "name": "recetas",
        "description": "Recipe Book (Libro de Recetas)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (35, 25, 20, 255),      # Dark cover outline
            "r": (180, 40, 40, 255),     # Red cover
            "m": (130, 25, 25, 255),     # Dark red shade
            "p": (245, 235, 215, 255),   # Cream pages
            "s": (200, 190, 170, 255),   # Pages shadow
            "g": (230, 190, 40, 255),    # Gold latch/bookmark
        },
        "grid": [
            "................",
            "......kkkkkk....",
            "....kkrrrrrrmk..",
            "...krrrrrrrrmmk.",
            "..krrkkkkkkrmmk.",
            "..krrkgpppskrmk.",
            "..krrkgppppskrmk",
            "..krrkgppppskrmk",
            "..krrkgppppskrmk",
            "..krrkgppppskrmk",
            "..krrkgpppskrmk.",
            "..krrkkkkkrmmk..",
            "...kmmmmmmmmk...",
            "....kkkkkkkk....",
            "................",
            "................"
        ]
    },
    {
        "name": "loot_tables",
        "description": "Treasure Chest (Cofre de Botín)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (25, 15, 10, 255),      # Dark outline
            "h": (195, 125, 45, 255),    # Light brown
            "w": (155, 95, 30, 255),     # Medium wood brown
            "d": (110, 65, 20, 255),     # Dark shadow brown
            "l": (240, 240, 240, 255),   # Latch white top
            "s": (180, 180, 180, 255),   # Latch grey bottom
        },
        "grid": [
            "................",
            "................",
            "................",
            "................",
            ".....kkkkkk.....",
            "....khhhhhdk....",
            "....khhkkhdk....",
            "....kkkllkkk....",
            "....khksskdk....",
            "....khhkkhdk....",
            "....kddddddk....",
            ".....kkkkkk.....",
            "................",
            "................",
            "................",
            "................"
        ]
    },
    {
        "name": "tags",
        "description": "Name Tag (Etiqueta)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (45, 45, 45, 255),      # Dark outline/string
            "w": (240, 235, 220, 255),   # Cream paper
            "s": (190, 185, 170, 255),   # Paper shadow
            "r": (160, 110, 80, 255),    # Rope ring
            "b": (100, 140, 180, 255),   # Text marks (blue)
        },
        "grid": [
            "......kk........",
            "....kk..kk......",
            "...k.rkk..k.....",
            "...kr.rk..k.....",
            "....krrkkkk.....",
            ".....kkwwk......",
            "......kwwwk.....",
            ".....kwwbwk.....",
            "....kwwbwwk.....",
            "...kwwbwwsk.....",
            "..kwwbwwwsk.....",
            ".kwwbwwwssk.....",
            ".kwwwwwsssk.....",
            ".kwwwssssk......",
            "..kkkkkkk.......",
            "................"
        ]
    },
    {
        "name": "comandos",
        "description": "Command Block (Bloque de Comandos)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (40, 20, 10, 255),      # Dark outline
            "o": (225, 125, 45, 255),    # Orange casing
            "h": (250, 175, 75, 255),    # Casing highlight
            "d": (160, 75, 25, 255),     # Casing shadow
            "w": (255, 255, 255, 255),   # Glow center
            "p": (220, 220, 220, 255),   # Panel light gray
            "g": (130, 130, 130, 255),   # Panel dark gray
        },
        "grid": [
            "................",
            "..kkkkkkkkkkkk..",
            "..khhhhhhhhhhk..",
            "..khwoooodoodk..",
            "..khwppppppddk..",
            "..khwpwwwwpddk..",
            "..khwpwggwpddk..",
            "..khwpwggwpddk..",
            "..khwpwwwwpddk..",
            "..khwppppppddk..",
            "..khwoooodoodk..",
            "..khoodoodoodk..",
            "..kddddddddddk..",
            "..kkkkkkkkkkkk..",
            "................",
            "................"
        ]
    },
    {
        "name": "alimentos",
        "description": "Apple (Manzana)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (45, 10, 10, 255),      # Dark red outline
            "r": (215, 35, 35, 255),     # Red skin
            "d": (145, 15, 15, 255),     # Dark shadow red
            "w": (255, 255, 255, 255),   # White shine
            "b": (90, 60, 45, 255),      # Stem brown
            "g": (80, 160, 50, 255),     # Leaf green
        },
        "grid": [
            "................",
            "......bbk.......",
            ".....kggbk......",
            "....kggkrrk.....",
            "....kkkrrrrk....",
            "...krrrwrrrdk...",
            "..krrrwrrrrrrk..",
            "..krrwrrrrrrdk..",
            "..krrrrrrrrrdk..",
            "..krrrrrrrrrdk..",
            "..krrrrrrrrrdk..",
            "...krrrrrrrdk...",
            "....krrrrrdk....",
            ".....kkddk......",
            "................",
            "................"
        ]
    },
    {
        "name": "cultivos",
        "description": "Wheat (Trigo)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (45, 35, 10, 255),      # Dark outline
            "y": (230, 180, 50, 255),     # Golden yellow
            "l": (250, 220, 100, 255),    # Bright yellow highlights
            "d": (170, 120, 20, 255),     # Wheat shadow
            "g": (100, 160, 50, 255),     # Green stems
            "s": (60, 110, 30, 255),      # Dark stem shadow
        },
        "grid": [
            "................",
            "......kk........",
            ".....klldk......",
            ".....klydk......",
            "....klyydkk.....",
            "....kllydkk.....",
            "...klyydkgk.....",
            "...kllydkggk....",
            "..klyydkkggsk...",
            "..kllyd.kggsk...",
            "..kyyd..kggsk...",
            "..kyd....ksk....",
            "..kk......k.....",
            "................",
            "................",
            "................"
        ]
    {
        "name": "logros",
        "description": "Golden Trophy (Trofeo de Logros)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (45, 30, 5, 255),       # Dark gold/brown outline
            "g": (240, 195, 35, 255),    # Gold yellow
            "h": (255, 235, 120, 255),   # Gold highlight
            "d": (175, 130, 10, 255),    # Gold shadow
            "s": (100, 100, 100, 255),   # Stone gray
            "c": (140, 140, 140, 255),   # Stone highlight
        },
        "grid": [
            "................",
            ".....kkkkkk.....",
            "....khgggghk....",
            "...khgggggghk...",
            "...khgkkkkgdk...",
            "...khgdkkkgdk...",
            "....kdggggdk....",
            ".....kgggdk.....",
            "......kggk......",
            "......kggk......",
            ".....khgghk.....",
            "....kssssssk....",
            "...kcccccccck...",
            "...kssssssssk...",
            "....kkkkkkkk....",
            "................"
        ]
    },
    {
        "name": "herramientas",
        "description": "Diamond Pickaxe (Pico de Diamante)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (15, 30, 40, 255),      # Dark outline
            "w": (220, 255, 255, 255),   # White shine
            "c": (100, 220, 255, 255),   # Cyan highlights
            "d": (50, 180, 220, 255),    # Medium cyan
            "b": (20, 110, 150, 255),    # Dark shadow cyan
            "s": (110, 75, 45, 255),     # Wood stick brown
        },
        "grid": [
            "......kkkkkkkk..",
            "....kkcddddwwbkk",
            "...kcddwwbbkkkk.",
            "..kddwwbk.sk....",
            ".kwwbbk...sk....",
            ".kbbk....sk.....",
            ".kk.....sk......",
            ".......sk.......",
            "......sk........",
            ".....sk.........",
            "....sk..........",
            "...sk...........",
            "..sk............",
            ".sk.............",
            "sk..............",
            "k..............."
        ]
    },
    {
        "name": "estructuras",
        "description": "Cobblestone Castle Tower (Torre de Estructuras)",
        "palette": {
            ".": (0, 0, 0, 0),          # Transparent
            "k": (30, 30, 30, 255),      # Dark outline
            "s": (128, 128, 128, 255),   # Stone gray
            "l": (170, 170, 170, 255),   # Light stone gray
            "d": (80, 80, 80, 255),      # Dark shadow stone
            "w": (120, 80, 50, 255),     # Wood door
            "r": (180, 40, 40, 255),     # Red roof flag
            "y": (230, 180, 50, 255),    # Yellow light
        },
        "grid": [
            "......kkkk......",
            "....kkrrrrkk....",
            "...krrrrrrrrk...",
            "..krrkkkkkkrrk..",
            "..krkllllllkrk..",
            "..kklddddddlkk..",
            "..ksldyyyyldsk..",
            "..ksldyyyyldsk..",
            "..ksldddddldsk..",
            "..ksllllllllsk..",
            "..kslkwwkllksk..",
            "..kslkwwkllksk..",
            "..kslkwwkllksk..",
            "..kddkkkkddkdk..",
            "..kkkkkkkkkkkk..",
            "................"
        ]
    }
]

def main():
    output_dir = "minecraft_icons"
    os.makedirs(output_dir, exist_ok=True)
    print(f"Directorio de destino: '{output_dir}'")
    
    for icon in icons_data:
        name = icon["name"]
        desc = icon["description"]
        palette = icon["palette"]
        grid = icon["grid"]
        
        # Create image
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        pixels = img.load()
        
        # Verify grid dimensions
        if len(grid) != 16:
            print(f"Error: La cuadrícula de '{name}' tiene {len(grid)} filas (esperado 16)")
            continue
            
        for y, row in enumerate(grid):
            if len(row) != 16:
                print(f"Error: La fila {y} de '{name}' tiene {len(row)} píxeles (esperado 16)")
                continue
            for x, char in enumerate(row):
                if char not in palette:
                    print(f"Error: Carácter '{char}' no está en la paleta de '{name}' en ({x}, {y})")
                    color = (0, 0, 0, 0)
                else:
                    color = palette[char]
                pixels[x, y] = color
                
        # Save output image
        output_path = os.path.join(output_dir, f"{name}.png")
        img.save(output_path)
        print(f"Icono creado: {output_path} ({desc})")

if __name__ == "__main__":
    main()
