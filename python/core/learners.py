"""Learners. Each is rebuilt from the deployment log, so it holds no hidden state.

- SharedLinUCB: one ridge-regression model over phi(x, a); score = mean + beta * sqrt(phi' A^-1 phi).
- UCB1Labels: non-contextual UCB1 over the four policy labels (evaluation baseline).
- choose_*: selection helpers with a seeded tie-break.
"""
from __future__ import annotations

import hashlib
import math
import random

import numpy as np

TIE_TOL = 1e-12


def seeded_rng(*keys) -> random.Random:
    """Deterministic RNG from stable keys (e.g. meal id), so a recommendation can be reproduced exactly."""
    digest = hashlib.sha256("|".join(map(str, keys)).encode("utf-8")).digest()
    return random.Random(int.from_bytes(digest[:8], "big"))


def argmax_seeded(scores: list[float], rng: random.Random) -> int:
    best = max(scores)
    ties = [i for i, s in enumerate(scores) if s >= best - TIE_TOL]
    return ties[0] if len(ties) == 1 else rng.choice(ties)


class SharedLinUCB:
    def __init__(self, dim: int, beta: float, ridge: float):
        self.dim, self.beta = dim, beta
        self.A = ridge * np.eye(dim)
        self.b = np.zeros(dim)
        self.n_updates = 0

    @classmethod
    def from_log(cls, log: list[dict], dim: int, beta: float, ridge: float) -> "SharedLinUCB":
        model = cls(dim, beta, ridge)
        for entry in log:
            model.update(entry["phi"], entry["reward"])
        return model

    def update(self, phi: list[float], reward: float) -> None:
        x = np.asarray(phi, dtype=float)
        if x.shape != (self.dim,):
            raise ValueError(f"phi must have {self.dim} entries")
        self.A += np.outer(x, x)
        self.b += reward * x
        self.n_updates += 1

    def theta(self) -> np.ndarray:
        return np.linalg.solve(self.A, self.b)

    def score(self, phi: list[float]) -> tuple[float, float, float]:
        """Returns (predicted mean, exploration bonus, UCB score)."""
        x = np.asarray(phi, dtype=float)
        mean = float(self.theta() @ x)
        bonus = float(self.beta * math.sqrt(max(x @ np.linalg.solve(self.A, x), 0.0)))
        return mean, bonus, mean + bonus


class UCB1Labels:
    """Classic UCB1 on rewards rescaled from [-clip, clip] to [0, 1]."""

    def __init__(self, labels=("P0", "P1", "P2", "P3"), clip: float = 3.0):
        self.labels, self.clip = list(labels), clip
        self.counts = {lbl: 0 for lbl in self.labels}
        self.sums = {lbl: 0.0 for lbl in self.labels}

    def update(self, label: str, reward: float) -> None:
        self.counts[label] += 1
        self.sums[label] += (reward + self.clip) / (2 * self.clip)

    def scores(self, available: list[str]) -> dict[str, float]:
        total = sum(self.counts.values())
        out = {}
        for lbl in available:
            n = self.counts[lbl]
            out[lbl] = math.inf if n == 0 else self.sums[lbl] / n + math.sqrt(2 * math.log(max(total, 1)) / n)
        return out
