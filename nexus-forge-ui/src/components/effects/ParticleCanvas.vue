<template>
  <canvas ref="canvasRef" class="particle-canvas"></canvas>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';

const canvasRef = ref<HTMLCanvasElement | null>(null);

interface Particle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  radius: number;
}

let animationId: number;
let particles: Particle[] = [];
let ctx: CanvasRenderingContext2D | null = null;

const PARTICLE_COUNT = 60;
const CONNECTION_DISTANCE = 120;
const MAX_CONNECTIONS = 3;

function resizeCanvas(canvas: HTMLCanvasElement) {
  const dpr = window.devicePixelRatio || 1;
  const rect = canvas.getBoundingClientRect();
  canvas.width = rect.width * dpr;
  canvas.height = rect.height * dpr;
  ctx?.scale(dpr, dpr);
}

function createParticles(width: number, height: number): Particle[] {
  return Array.from({ length: PARTICLE_COUNT }, () => ({
    x: Math.random() * width,
    y: Math.random() * height,
    vx: (Math.random() - 0.5) * 0.5,
    vy: (Math.random() - 0.5) * 0.5,
    radius: Math.random() * 2 + 1,
  }));
}

function draw(width: number, height: number) {
  if (!ctx) return;

  ctx.clearRect(0, 0, width, height);

  particles.forEach((particle) => {
    particle.x += particle.vx;
    particle.y += particle.vy;

    if (particle.x < 0 || particle.x > width) particle.vx *= -1;
    if (particle.y < 0 || particle.y > height) particle.vy *= -1;

    ctx!.beginPath();
    ctx!.arc(particle.x, particle.y, particle.radius, 0, Math.PI * 2);
    ctx!.fillStyle = 'rgba(255, 255, 255, 0.6)';
    ctx!.fill();
  });

  for (let i = 0; i < particles.length; i++) {
    let connections = 0;
    for (let j = i + 1; j < particles.length; j++) {
      const dx = particles[i].x - particles[j].x;
      const dy = particles[i].y - particles[j].y;
      const distance = Math.hypot(dx, dy);

      if (distance < CONNECTION_DISTANCE && connections < MAX_CONNECTIONS) {
        ctx.beginPath();
        ctx.moveTo(particles[i].x, particles[i].y);
        ctx.lineTo(particles[j].x, particles[j].y);
        ctx.strokeStyle = `rgba(255, 255, 255, ${0.15 * (1 - distance / CONNECTION_DISTANCE)})`;
        ctx.lineWidth = 0.8;
        ctx.stroke();
        connections++;
      }
    }
  }

  animationId = requestAnimationFrame(() => draw(width, height));
}

onMounted(() => {
  const canvas = canvasRef.value;
  if (!canvas) return;

  ctx = canvas.getContext('2d');
  resizeCanvas(canvas);

  const rect = canvas.getBoundingClientRect();
  particles = createParticles(rect.width, rect.height);

  draw(rect.width, rect.height);

  const handleResize = () => {
    resizeCanvas(canvas);
    const newRect = canvas.getBoundingClientRect();
    particles = createParticles(newRect.width, newRect.height);
  };

  window.addEventListener('resize', handleResize);

  onUnmounted(() => {
    cancelAnimationFrame(animationId);
    window.removeEventListener('resize', handleResize);
  });
});
</script>

<style scoped lang="scss">
.particle-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
</style>
