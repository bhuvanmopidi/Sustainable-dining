"""Keyed randomness: every draw is keyed by (seed, meal, purpose), never by call order.

This keeps exogenous conditions identical across policies: a policy that triggers
extra computation can never shift the random numbers used for a later meal.
"""
import hashlib

import numpy as np


def keyed_rng(*keys) -> np.random.Generator:
    digest = hashlib.sha256("|".join(map(str, keys)).encode("utf-8")).digest()
    return np.random.default_rng(int.from_bytes(digest[:8], "big"))