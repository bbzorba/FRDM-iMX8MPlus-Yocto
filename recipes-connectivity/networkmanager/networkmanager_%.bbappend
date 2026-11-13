# A .bbappend modifies a recipe from another layer without copying it.
# "_%" matches any version, so this keeps working when meta-networking bumps NM.

# Turn on the text UI and Wi-Fi support explicitly (wifi is otherwise only on
# when 'wifi' is in DISTRO_FEATURES).
PACKAGECONFIG:append = " nmtui wifi"
