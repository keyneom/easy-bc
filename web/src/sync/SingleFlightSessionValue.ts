/** Keeps one sensitive value in tab memory and coalesces concurrent loads. */
export class SingleFlightSessionValue<T> {
  private cached: T | null = null;
  private pending: Promise<T> | null = null;
  private generation = 0;

  getOrLoad(loader: () => Promise<T>): Promise<T> {
    if (this.cached !== null) return Promise.resolve(this.cached);
    if (this.pending) return this.pending;

    const loadGeneration = this.generation;
    const pending = loader()
      .then((value) => {
        if (this.generation === loadGeneration) this.cached = value;
        return value;
      })
      .finally(() => {
        if (this.pending === pending) this.pending = null;
      });
    this.pending = pending;
    return pending;
  }

  get(): T | null {
    return this.cached;
  }

  clear(): void {
    this.generation += 1;
    this.cached = null;
    this.pending = null;
  }
}
