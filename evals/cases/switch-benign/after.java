class Subject { int price(int tier) { return switch(tier) { case 1 -> 10; default -> 20; }; } }
