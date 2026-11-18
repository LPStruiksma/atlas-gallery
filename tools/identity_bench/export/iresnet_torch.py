"""A minimal PyTorch IResNet (the arcface_torch layout), enough to load GHOST's backbone.pth for export.

Written from the architecture (Deng et al., ArcFace; the arcface_torch state-dict names), not copied.
Imported only when PyTorch is installed.
"""
import torch
from torch import nn


class Block(nn.Module):
    def __init__(self, inplanes: int, planes: int, stride: int):
        super().__init__()
        self.bn1 = nn.BatchNorm2d(inplanes, eps=1e-5)
        self.conv1 = nn.Conv2d(inplanes, planes, 3, 1, 1, bias=False)
        self.bn2 = nn.BatchNorm2d(planes, eps=1e-5)
        self.prelu = nn.PReLU(planes)
        self.conv2 = nn.Conv2d(planes, planes, 3, stride, 1, bias=False)
        self.bn3 = nn.BatchNorm2d(planes, eps=1e-5)
        self.downsample = None
        if stride != 1 or inplanes != planes:
            self.downsample = nn.Sequential(nn.Conv2d(inplanes, planes, 1, stride, bias=False),
                                            nn.BatchNorm2d(planes, eps=1e-5))

    def forward(self, x):
        out = self.bn3(self.conv2(self.prelu(self.bn2(self.conv1(self.bn1(x))))))
        return out + (x if self.downsample is None else self.downsample(x))


class IResNet(nn.Module):
    def __init__(self, layers=(3, 13, 30, 3), features: int = 512):
        super().__init__()
        self.conv1 = nn.Conv2d(3, 64, 3, 1, 1, bias=False)
        self.bn1 = nn.BatchNorm2d(64, eps=1e-5)
        self.prelu = nn.PReLU(64)
        inplanes = 64
        for i, (n, planes) in enumerate(zip(layers, (64, 128, 256, 512)), start=1):
            blocks = [Block(inplanes, planes, 2)] + [Block(planes, planes, 1) for _ in range(n - 1)]
            setattr(self, f"layer{i}", nn.Sequential(*blocks))
            inplanes = planes
        self.bn2 = nn.BatchNorm2d(512, eps=1e-5)
        self.dropout = nn.Dropout(0.0)
        self.fc = nn.Linear(512 * 7 * 7, features)
        self.features = nn.BatchNorm1d(features, eps=1e-5)

    def forward(self, x):
        x = self.prelu(self.bn1(self.conv1(x)))
        x = self.layer4(self.layer3(self.layer2(self.layer1(x))))
        x = torch.flatten(self.dropout(self.bn2(x)), 1)
        return self.features(self.fc(x))


def iresnet100(features: int = 512) -> IResNet:
    return IResNet((3, 13, 30, 3), features)


def tiny(features: int = 512) -> IResNet:
    """One block per stage, as the offline tests' synthetic state dict."""
    return IResNet((1, 1, 1, 1), features)
